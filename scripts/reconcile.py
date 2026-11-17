#!/usr/bin/env python3
"""
Spark Reconciliation Batch Job  (FR-REC-1, FR-REC-2, FR-REC-3)
===============================================================
Compares per-account transfer totals stored in two independent
data stores:
  - SOURCE OF TRUTH  : PostgreSQL transactions table (double-entry ledger)
  - AUDIT STORE      : Cassandra account_audit_events table

Expected invariant for every account:
  Postgres net movement (sum of DEBIT rows - sum of CREDIT rows)
  equals
  Cassandra net movement (sum of DEBIT rows - sum of CREDIT rows)

Exit code 0 → PASS (all accounts reconcile)
Exit code 1 → FAIL (mismatches found or connection error)

Usage:
  # With running docker-compose stack:
  python3 scripts/reconcile.py

  # Or with explicit overrides:
  POSTGRES_URL=jdbc:postgresql://localhost:5432/transdb \\
  POSTGRES_USER=user POSTGRES_PASSWORD=pass \\
  CASSANDRA_HOST=localhost CASSANDRA_PORT=9042 \\
  python3 scripts/reconcile.py
"""

import os
import sys
import json
import datetime
import decimal

# ──────────────────────────────────────────────────────────────
# Config from environment
# ──────────────────────────────────────────────────────────────
PG_HOST     = os.environ.get("POSTGRES_HOST", "localhost")
PG_PORT     = os.environ.get("POSTGRES_PORT", "5432")
PG_DB       = os.environ.get("POSTGRES_DB", os.environ.get("POSTGRES_DB", "transdb"))
PG_USER     = os.environ.get("POSTGRES_USER", "postgres")
PG_PASSWORD = os.environ.get("POSTGRES_PASSWORD", "postgres")

CASS_HOST   = os.environ.get("CASSANDRA_HOST", "localhost")
CASS_PORT   = int(os.environ.get("CASSANDRA_PORT", "9042"))
CASS_DC     = os.environ.get("CASSANDRA_DATACENTER", "datacenter1")
CASS_KS     = os.environ.get("CASSANDRA_KEYSPACE", "transfer_audit")

REPORT_DIR  = os.environ.get("REPORT_DIR", "reports")


def load_postgres_totals():
    """
    Returns dict[account_id_str -> Decimal]:
      Net movement per account from the authoritative Postgres ledger.
      DEBIT  = money leaving this account  (negative net)
      CREDIT = money arriving at this account (positive net)
    """
    try:
        import psycopg2
        import psycopg2.extras
    except ImportError:
        raise RuntimeError("psycopg2-binary not installed – run: pip install psycopg2-binary")

    conn = psycopg2.connect(
        host=PG_HOST, port=PG_PORT, dbname=PG_DB,
        user=PG_USER, password=PG_PASSWORD
    )
    cur = conn.cursor(cursor_factory=psycopg2.extras.DictCursor)

    # Flatten double-entry: each tx contributes -amount to sender and +amount to receiver
    cur.execute("""
        SELECT from_account_id::text AS account_id, -SUM(amount) AS net
        FROM transactions WHERE status = 'COMPLETED'
        GROUP BY from_account_id
        UNION ALL
        SELECT to_account_id::text   AS account_id, +SUM(amount) AS net
        FROM transactions WHERE status = 'COMPLETED'
        GROUP BY to_account_id
    """)
    rows = cur.fetchall()
    cur.close()
    conn.close()

    totals: dict[str, decimal.Decimal] = {}
    for row in rows:
        aid = row["account_id"]
        totals[aid] = totals.get(aid, decimal.Decimal(0)) + decimal.Decimal(str(row["net"]))
    return totals


def load_cassandra_totals():
    """
    Returns dict[account_id_str -> Decimal]:
      Net movement per account from the Cassandra audit store.
    """
    try:
        from cassandra.cluster import Cluster
        from cassandra.policies import DCAwareRoundRobinPolicy
    except ImportError:
        raise RuntimeError("cassandra-driver not installed – run: pip install cassandra-driver")

    cluster = Cluster(
        [CASS_HOST],
        port=CASS_PORT,
        load_balancing_policy=DCAwareRoundRobinPolicy(local_dc=CASS_DC)
    )
    session = cluster.connect(CASS_KS)

    rows = session.execute(
        "SELECT account_id, direction, amount FROM account_audit_events"
    )
    session.shutdown()
    cluster.shutdown()

    totals: dict[str, decimal.Decimal] = {}
    for row in rows:
        aid = str(row.account_id)
        amt = decimal.Decimal(str(row.amount))
        sign = decimal.Decimal(-1) if row.direction == "DEBIT" else decimal.Decimal(1)
        totals[aid] = totals.get(aid, decimal.Decimal(0)) + sign * amt
    return totals


def reconcile_with_spark(pg_totals: dict, cass_totals: dict) -> tuple[list[dict], str]:
    """
    Uses PySpark to join and diff the two datasets.
    Falls back to pure-Python join if pyspark isn't available.
    Returns (mismatches, engine_used).
    """
    try:
        from pyspark.sql import SparkSession
        from pyspark.sql import functions as F
        from pyspark.sql.types import StructType, StructField, StringType, DecimalType

        spark = (SparkSession.builder
                 .appName("TransferReconciliation")
                 .master("local[*]")
                 .config("spark.ui.showConsoleProgress", "false")
                 .config("spark.sql.shuffle.partitions", "4")
                 .getOrCreate())
        spark.sparkContext.setLogLevel("ERROR")

        schema = StructType([
            StructField("account_id", StringType(), False),
            StructField("net", DecimalType(18, 4), False),
        ])

        pg_rows   = [(k, float(v)) for k, v in pg_totals.items()]
        cass_rows = [(k, float(v)) for k, v in cass_totals.items()]

        pg_df   = spark.createDataFrame(pg_rows,   ["account_id", "pg_net"])
        cass_df = spark.createDataFrame(cass_rows, ["account_id", "cass_net"])

        joined = pg_df.join(cass_df, on="account_id", how="full_outer")
        joined = joined.fillna(0.0)

        tolerance = 0.0001
        mismatches_df = joined.filter(
            F.abs(F.col("pg_net") - F.col("cass_net")) > tolerance
        )

        mismatches = [
            {"account_id": r["account_id"],
             "pg_net": r["pg_net"],
             "cass_net": r["cass_net"],
             "delta": abs(r["pg_net"] - (r["cass_net"] or 0))}
            for r in mismatches_df.collect()
        ]
        spark.stop()
        return mismatches, "PySpark 3.5"

    except (ImportError, Exception) as _spark_err:
        # Pure-Python fallback
        all_accounts = set(pg_totals) | set(cass_totals)
        mismatches = []
        for aid in all_accounts:
            pg_val   = float(pg_totals.get(aid, decimal.Decimal(0)))
            cass_val = float(cass_totals.get(aid, decimal.Decimal(0)))
            if abs(pg_val - cass_val) > 0.0001:
                mismatches.append({
                    "account_id": aid,
                    "pg_net": pg_val,
                    "cass_net": cass_val,
                    "delta": abs(pg_val - cass_val)
                })
        return mismatches, "Python fallback"


def write_report(mismatches: list[dict], engine: str, pg_totals: dict, cass_totals: dict):
    os.makedirs(REPORT_DIR, exist_ok=True)
    ts = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    path = os.path.join(REPORT_DIR, f"reconciliation_{ts}.json")
    report = {
        "run_at": ts,
        "engine": engine,
        "result": "FAIL" if mismatches else "PASS",
        "total_accounts_postgres": len(pg_totals),
        "total_accounts_cassandra": len(cass_totals),
        "mismatches_count": len(mismatches),
        "mismatches": mismatches,
    }
    with open(path, "w") as f:
        json.dump(report, f, indent=2)
    return path, report


def main():
    print("=" * 60)
    print("Transfer Platform Reconciliation Job (FR-REC-1..3)")
    print("=" * 60)

    # 1. Load Postgres totals
    print("\n[1/3] Loading Postgres ledger totals...")
    try:
        pg_totals = load_postgres_totals()
        print(f"      Loaded {len(pg_totals)} accounts from Postgres.")
    except Exception as e:
        print(f"ERROR loading Postgres data: {e}")
        sys.exit(1)

    # 2. Load Cassandra totals
    print("[2/3] Loading Cassandra audit totals...")
    try:
        cass_totals = load_cassandra_totals()
        print(f"      Loaded {len(cass_totals)} accounts from Cassandra.")
    except Exception as e:
        print(f"WARNING: Could not load Cassandra data: {e}")
        print("         Cassandra may be offline – treating all audit totals as 0.0")
        cass_totals = {}

    # 3. Reconcile with Spark
    print("[3/3] Running Spark reconciliation join...")
    mismatches, engine = reconcile_with_spark(pg_totals, cass_totals)
    report_path, report = write_report(mismatches, engine, pg_totals, cass_totals)

    print("\n" + "=" * 60)
    print(f"Engine  : {engine}")
    print(f"Accounts: Postgres={len(pg_totals)}, Cassandra={len(cass_totals)}")
    print(f"Result  : {report['result']}")
    print(f"Report  : {report_path}")
    if mismatches:
        print(f"\n{'MISMATCH DETAILS':^60}")
        for m in mismatches:
            print(f"  account={m['account_id'][:8]}...  "
                  f"pg={m['pg_net']:.4f}  cass={m['cass_net']:.4f}  "
                  f"delta={m['delta']:.4f}")
    print("=" * 60)

    sys.exit(0 if not mismatches else 1)


if __name__ == "__main__":
    main()
