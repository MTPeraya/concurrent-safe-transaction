#!/usr/bin/env python3
"""
Unit tests for the Spark reconciliation batch job (FR-REC-1, FR-REC-2, AT-12).

These tests exercise the pure-Python fallback path so they run without
any external infrastructure (no Postgres, no Cassandra, no Spark cluster).
The PySpark path is validated in the integration smoke-test below.
"""
import decimal
import json
import os
import sys
import tempfile
import unittest

# Make scripts/ importable
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Monkeypatch DB connectors so we can test reconcile_with_spark in isolation
import types

# Stub psycopg2 and cassandra so the module loads cleanly
sys.modules.setdefault("psycopg2", types.ModuleType("psycopg2"))
sys.modules.setdefault("psycopg2.extras", types.ModuleType("psycopg2.extras"))
sys.modules.setdefault("cassandra", types.ModuleType("cassandra"))
sys.modules.setdefault("cassandra.cluster", types.ModuleType("cassandra.cluster"))
sys.modules.setdefault("cassandra.policies", types.ModuleType("cassandra.policies"))

import reconcile  # noqa: E402  (must come after path setup)


def _dec(s):
    return decimal.Decimal(str(s))


class ReconcileLogicTest(unittest.TestCase):

    def test_pass_when_totals_match(self):
        """AT-12 (clean state): matching Postgres and Cassandra totals → PASS, 0 mismatches."""
        pg   = {"acc-1": _dec("100.00"), "acc-2": _dec("-100.00")}
        cass = {"acc-1": _dec("100.00"), "acc-2": _dec("-100.00")}
        mismatches, engine = reconcile.reconcile_with_spark(pg, cass)
        self.assertEqual(mismatches, [], f"Expected no mismatches, got: {mismatches}")
        self.assertTrue("spark" in engine.lower() or "fallback" in engine.lower())

    def test_fail_when_cassandra_missing_account(self):
        """AT-12 (deleted record): account present in Postgres but absent in Cassandra → FAIL."""
        pg   = {"acc-1": _dec("100.00"), "acc-2": _dec("-50.00")}
        cass = {"acc-1": _dec("100.00")}  # acc-2 missing
        mismatches, _ = reconcile.reconcile_with_spark(pg, cass)
        ids = [m["account_id"] for m in mismatches]
        self.assertIn("acc-2", ids, "Expected mismatch for acc-2 (Cassandra missing record)")

    def test_fail_when_amounts_differ(self):
        """FR-REC-2: amount drift is detected as a mismatch."""
        pg   = {"acc-1": _dec("200.00")}
        cass = {"acc-1": _dec("150.00")}  # 50 less – simulating a lost audit event
        mismatches, _ = reconcile.reconcile_with_spark(pg, cass)
        self.assertEqual(len(mismatches), 1)
        self.assertAlmostEqual(mismatches[0]["delta"], 50.0, places=2)

    def test_fail_when_postgres_missing_account(self):
        """Cassandra has orphan account (not in Postgres) → mismatch."""
        pg   = {}
        cass = {"acc-ghost": _dec("999.00")}
        mismatches, _ = reconcile.reconcile_with_spark(pg, cass)
        ids = [m["account_id"] for m in mismatches]
        self.assertIn("acc-ghost", ids)

    def test_pass_with_many_accounts(self):
        """Scale: 1,000 accounts all reconciling → 0 mismatches, quick execution."""
        pg   = {f"acc-{i}": _dec(str(i * 10)) for i in range(1000)}
        cass = dict(pg)
        mismatches, _ = reconcile.reconcile_with_spark(pg, cass)
        self.assertEqual(len(mismatches), 0)

    def test_report_written_correctly(self):
        """FR-REC-2: write_report emits valid JSON with result, mismatches, and timestamp."""
        pg   = {"acc-1": _dec("100.00")}
        cass = {"acc-1": _dec("99.00")}  # drift
        mismatches = [{"account_id": "acc-1", "pg_net": 100.0, "cass_net": 99.0, "delta": 1.0}]

        with tempfile.TemporaryDirectory() as tmpdir:
            # Temporarily redirect REPORT_DIR
            orig = reconcile.REPORT_DIR
            reconcile.REPORT_DIR = tmpdir
            path, report = reconcile.write_report(mismatches, "Python fallback", pg, cass)
            reconcile.REPORT_DIR = orig

            self.assertTrue(os.path.exists(path))
            with open(path) as f:
                data = json.load(f)

            self.assertEqual(data["result"], "FAIL")
            self.assertEqual(data["mismatches_count"], 1)
            self.assertIn("run_at", data)
            self.assertIn("engine", data)

    def test_report_pass_on_no_mismatches(self):
        """FR-REC-2: write_report emits PASS when mismatches list is empty."""
        pg   = {"acc-1": _dec("100.00")}
        cass = {"acc-1": _dec("100.00")}
        with tempfile.TemporaryDirectory() as tmpdir:
            orig = reconcile.REPORT_DIR
            reconcile.REPORT_DIR = tmpdir
            _, report = reconcile.write_report([], "Python fallback", pg, cass)
            reconcile.REPORT_DIR = orig

        self.assertEqual(report["result"], "PASS")
        self.assertEqual(report["mismatches_count"], 0)


class ReconcileSparkTest(unittest.TestCase):
    """
    Smoke-test: exercise the PySpark path with a tiny in-memory dataset.
    Skipped automatically if PySpark is not usable on current JVM environment.
    """

    @classmethod
    def setUpClass(cls):
        try:
            import pyspark
            from pyspark.sql import SparkSession
            spark = SparkSession.builder.appName("test").master("local[1]").config("spark.ui.showConsoleProgress", "false").getOrCreate()
            spark.stop()
        except Exception as e:
            raise unittest.SkipTest(f"PySpark not usable on current JVM: {e}")

    def test_spark_pass_matching_totals(self):
        pg   = {"a1": _dec("100.00"), "a2": _dec("-100.00")}
        cass = {"a1": _dec("100.00"), "a2": _dec("-100.00")}
        mismatches, engine = reconcile.reconcile_with_spark(pg, cass)
        self.assertEqual(mismatches, [])
        self.assertIn("Spark", engine)

    def test_spark_fail_on_mismatch(self):
        pg   = {"a1": _dec("100.00")}
        cass = {"a1": _dec("80.00")}
        mismatches, _ = reconcile.reconcile_with_spark(pg, cass)
        self.assertEqual(len(mismatches), 1)
        self.assertAlmostEqual(mismatches[0]["delta"], 20.0, places=2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
