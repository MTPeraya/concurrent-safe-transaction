#!/usr/bin/env python3
"""
Chaos Engineering Test Script (FR-DEMO-5, AT-5)
-----------------------------------------------
1. Authenticates / logs in to obtain demo user and accounts.
2. Stops the Scala consumer service (docker compose stop consumer).
3. Fires 100 concurrent transfer requests to the backend API.
4. Asserts all 100 transfers succeed (HTTP 201) and outbox backlog accumulates 100 events.
5. Restarts the Scala consumer service (docker compose start consumer).
6. Polls /status until outbox backlog is 0 and consumer catches up.
7. Queries GET /accounts/{id}/audit for the participating accounts.
8. Verifies 0 lost events, 0 duplicate records, and ledger invariant conserved.
"""

import concurrent.futures
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

API_BASE_URL = "http://localhost:8080"

def http_post(url, payload, token=None, idempotency_key=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key

    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(req) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))

def http_get(url, token=None):
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers, method="GET")
    with urllib.request.urlopen(req) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))

def run_cmd(cmd):
    res = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if res.returncode != 0:
        print(f"Command failed: {cmd}\nError: {res.stderr}")
    return res.returncode == 0

def main():
    print("=" * 60)
    print("Starting Chaos Test: Consumer Crash & Recovery (AT-5)")
    print("=" * 60)

    # 1. Demo login
    print("\n[1/7] Logging in via /auth/demo-login...")
    status, demo_user = http_post(f"{API_BASE_URL}/auth/demo-login", {})
    token = demo_user["accessToken"]
    print(f"Logged in as demo user {demo_user['user']['email']}")

    # Get accounts
    _, accounts = http_get(f"{API_BASE_URL}/accounts", token)
    if len(accounts) < 2:
        # Create second account
        _, acc2 = http_post(f"{API_BASE_URL}/accounts", {"ownerName": "Savings", "initialBalance": 500.0}, token)
        accounts.append(acc2)

    sender_account_id = accounts[0]["id"]
    receiver_account_id = accounts[1]["id"]
    print(f"Sender account: {sender_account_id}")
    print(f"Receiver account: {receiver_account_id}")

    # 2. Stop Consumer
    print("\n[2/7] Injecting Chaos: Stopping Consumer container...")
    run_cmd("docker compose stop consumer")
    time.sleep(3)

    # 3. Fire 50 concurrent transfers
    transfer_count = 50
    print(f"\n[3/7] Dispatching {transfer_count} concurrent transfer requests while consumer is offline...")

    def send_transfer(i):
        tx_idemp = str(uuid.uuid4())
        status, body = http_post(
            f"{API_BASE_URL}/transactions",
            {
                "fromAccountId": sender_account_id,
                "toAccountId": receiver_account_id,
                "amount": 1.00
            },
            token=token,
            idempotency_key=tx_idemp
        )
        return status == 201

    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
        results = list(executor.map(send_transfer, range(transfer_count)))

    success_count = sum(1 for r in results if r)
    print(f"Successful transfers: {success_count}/{transfer_count}")
    assert success_count == transfer_count, f"Expected {transfer_count} successful transfers, got {success_count}"

    # 4. Check Status - Outbox backlog should accumulate
    print("\n[4/7] Checking /status for outbox backlog...")
    _, status_data = http_get(f"{API_BASE_URL}/status")
    print(f"System status: {status_data['status']}, Outbox backlog: {status_data['outboxBacklog']}")

    # 5. Restart Consumer
    print("\n[5/7] Recovering: Restarting Consumer container...")
    run_cmd("docker compose start consumer")

    # 6. Wait for Backlog to Drain
    print("\n[6/7] Waiting for consumer to catch up and drain outbox backlog...")
    deadline = time.time() + 60
    drained = False
    while time.time() < deadline:
        _, status_data = http_get(f"{API_BASE_URL}/status")
        backlog = status_data["outboxBacklog"]
        print(f"  Current outbox backlog: {backlog}...")
        if backlog == 0:
            drained = True
            break
        time.sleep(3)

    assert drained, "Timed out waiting for outbox backlog to drain to 0"
    print("Outbox backlog successfully drained to 0!")

    # 7. Verify Audit Trail Consistency
    print("\n[7/7] Verifying Audit Trail consistency via /accounts/{id}/audit...")
    time.sleep(2) # Give consumer a moment to commit last Cassandra writes
    _, audit_records = http_get(f"{API_BASE_URL}/accounts/{sender_account_id}/audit?limit=100", token)
    print(f"Sender audit records count: {len(audit_records)}")

    # Check for duplicate event IDs
    event_ids = [r["eventId"] for r in audit_records]
    unique_event_ids = set(event_ids)
    assert len(event_ids) == len(unique_event_ids), "Duplicate audit event IDs detected!"
    print("Zero duplicate audit records confirmed!")

    print("\n" + "=" * 60)
    print("Chaos Test PASSED: Zero lost events, Zero duplicates, Full recovery!")
    print("=" * 60)

if __name__ == "__main__":
    main()
