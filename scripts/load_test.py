#!/usr/bin/env python3
"""
Concurrent Load Testing Script for Concurrent-Safe Transaction Backend
Measures HTTP throughput, latency (avg, p95), success rate, and balance conservation.
"""

import time
import uuid
import json
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE_URL = "http://localhost:8080"

def http_post(path, data, token=None, idempotency_key=None):
    url = f"{BASE_URL}{path}"
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    
    req = urllib.request.Request(
        url,
        data=json.dumps(data).encode("utf-8") if data else None,
        headers=headers,
        method="POST"
    )
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            latency = (time.perf_counter() - start) * 1000.0
            return response.status, json.loads(response.read().decode("utf-8")), latency
    except urllib.error.HTTPError as e:
        latency = (time.perf_counter() - start) * 1000.0
        body = e.read().decode("utf-8")
        try:
            parsed = json.loads(body)
        except Exception:
            parsed = {"raw": body}
        return e.code, parsed, latency
    except Exception as e:
        latency = (time.perf_counter() - start) * 1000.0
        return 500, {"error": str(e)}, latency

def http_get(path, token=None):
    url = f"{BASE_URL}{path}"
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    
    req = urllib.request.Request(url, headers=headers, method="GET")
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            latency = (time.perf_counter() - start) * 1000.0
            return response.status, json.loads(response.read().decode("utf-8")), latency
    except urllib.error.HTTPError as e:
        latency = (time.perf_counter() - start) * 1000.0
        return e.code, e.read().decode("utf-8"), latency

def run_load_test(num_transfers=50, max_workers=10):
    print(f"=== Starting Load Test: {num_transfers} transfers across {max_workers} concurrent workers ===")
    
    # 1. Setup User and Accounts
    user_email = f"loadtest_{uuid.uuid4().hex[:8]}@example.com"
    password = "Password123!"
    
    status, reg_body, _ = http_post("/auth/register", {"email": user_email, "password": password})
    assert status == 201, f"Failed registration: {reg_body}"
    
    status, login_body, _ = http_post("/auth/login", {"email": user_email, "password": password})
    assert status == 200, f"Failed login: {login_body}"
    token = login_body["accessToken"]
    
    initial_sender_balance = 1000.00
    status, sender_acc, _ = http_post("/accounts", {"ownerName": "Load Sender", "initialBalance": initial_sender_balance}, token=token)
    sender_id = sender_acc["id"]
    
    status, receiver_acc, _ = http_post("/accounts", {"ownerName": "Load Receiver", "initialBalance": 0.00}, token=token)
    receiver_id = receiver_acc["id"]
    
    print(f"Created accounts: Sender={sender_id} ($1000.00), Receiver={receiver_id} ($0.00)")
    
    transfer_amount = 10.00
    latencies = []
    successes = 0
    failures = 0
    
    start_total = time.perf_counter()
    
    def execute_single_transfer(i):
        key = str(uuid.uuid4())
        payload = {
            "fromAccountId": sender_id,
            "toAccountId": receiver_id,
            "amount": transfer_amount
        }
        return http_post("/transactions", payload, token=token, idempotency_key=key)

    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = [executor.submit(execute_single_transfer, i) for i in range(num_transfers)]
        for f in as_completed(futures):
            code, body, lat = f.result()
            latencies.append(lat)
            if code in (200, 201):
                successes += 1
            else:
                failures += 1
                print(f"Failed transfer: {code} -> {body}")

    total_time_s = time.perf_counter() - start_total
    
    # Verify final balances
    _, sender_final, _ = http_get(f"/accounts/{sender_id}", token=token)
    _, receiver_final, _ = http_get(f"/accounts/{receiver_id}", token=token)
    
    latencies.sort()
    avg_latency = sum(latencies) / len(latencies)
    p95_latency = latencies[int(len(latencies) * 0.95)]
    throughput = num_transfers / total_time_s
    
    print("\n========== LOAD TEST RESULTS ==========")
    print(f"Total Requests:     {num_transfers}")
    print(f"Successful (201):   {successes}")
    print(f"Failed:             {failures}")
    print(f"Total Duration:     {total_time_s:.2f} s")
    print(f"Throughput:         {throughput:.2f} req/sec")
    print(f"Avg Latency:        {avg_latency:.2f} ms")
    print(f"p95 Latency:        {p95_latency:.2f} ms")
    print(f"Sender Balance:     ${sender_final['balance']:.2f}")
    print(f"Receiver Balance:   ${receiver_final['balance']:.2f}")
    total_money = sender_final['balance'] + receiver_final['balance']
    print(f"Total Conserved:    ${total_money:.2f} (Expected: $1000.00)")
    print("=======================================\n")
    
    assert total_money == 1000.00, f"Money not conserved! Got: {total_money}"
    assert successes == num_transfers, f"Expected {num_transfers} successes, got {successes}"

if __name__ == "__main__":
    run_load_test(50, 10)

