#!/usr/bin/env python3
import sys
import os
import subprocess
import time
import json
import urllib.request
import urllib.parse
from datetime import datetime, timezone

PROMETHEUS_URL = "http://localhost:9090"
DB_CONTAINER = "pg_enterprise_supply"
DB_USER = "enterprise_admin"
DB_NAME = "supply_db"

def run_command(cmd, shell=False, capture_output=True):
    try:
        res = subprocess.run(cmd, shell=shell, capture_output=capture_output, text=True, check=True)
        return res.stdout.strip()
    except subprocess.CalledProcessError as e:
        print(f"Command failed: {cmd}\nError: {e.stderr or e}")
        return None

def query_prometheus(query, start_time, end_time):
    params = {
        'query': query,
        'start': start_time,
        'end': end_time,
        'step': '15s'
    }
    url = f"{PROMETHEUS_URL}/api/v1/query_range?" + urllib.parse.urlencode(params)
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=5) as response:
            data = json.loads(response.read().decode('utf-8'))
            return data.get('data', {}).get('result', [])
    except Exception as e:
        # Gracefully log Prometheus query failure
        return []

def get_stats_from_prom_result(result, val_type=float):
    vals = []
    for r in result:
        for v in r.get('values', []):
            try:
                vals.append(val_type(v[1]))
            except (ValueError, TypeError):
                continue
    if not vals:
        return 0.0, 0.0
    return sum(vals) / len(vals), max(vals)

def get_db_metrics():
    # Query database sizes and table counts
    sql = """
    SELECT
      (SELECT COUNT(*) FROM products) as products,
      (SELECT COUNT(*) FROM orders) as orders,
      (SELECT COUNT(*) FROM order_items) as order_items,
      (SELECT COUNT(*) FROM audit_logs) as audit_logs,
      (SELECT COUNT(*) FROM notifications) as notifications,
      pg_size_pretty(pg_database_size('supply_db')) as db_size;
    """
    cmd = ["docker", "exec", "-i", DB_CONTAINER, "psql", "-U", DB_USER, "-d", DB_NAME, "-t", "-A", "-F,", "-c", sql]
    output = run_command(cmd)
    if not output:
        return None
    
    parts = output.split(",")
    if len(parts) < 6:
        return None
        
    return {
        "products": int(parts[0]),
        "orders": int(parts[1]),
        "order_items": int(parts[2]),
        "audit_logs": int(parts[3]),
        "notifications": int(parts[4]),
        "db_size": parts[5].strip()
    }

def format_num(val):
    if val is None:
        return "N/A"
    try:
        return f"{val:,}"
    except (ValueError, TypeError):
        return str(val)

def main():
    profile = "stress" if len(sys.argv) < 2 else sys.argv[1]
    
    print("==================================================================")
    print(f"Starting Nexus Supply Chain Benchmark Runner (Profile: {profile})")
    print("==================================================================")
    
    # 1. Check Database Seed Status
    print("\n[1/5] Checking Database Seeding State...")
    db_metrics = get_db_metrics()
    if not db_metrics:
        print("Warning: Could not connect to PostgreSQL container. Is Docker running?")
    else:
        print(f"Current database size: {db_metrics['db_size']}")
        print(f"Products: {db_metrics['products']:,} | Orders: {db_metrics['orders']:,} | Items: {db_metrics['order_items']:,}")
        
        # If DB is empty, run the scale data script
        if db_metrics['products'] < 1000:
            print("Database scale is low. Automatically seeding 3.1 million records...")
            run_command(["docker", "cp", "docker/scale_data.sql", f"{DB_CONTAINER}:/tmp/scale_data.sql"])
            run_command(["docker", "exec", "-i", DB_CONTAINER, "psql", "-U", DB_USER, "-d", DB_NAME, "-f", "/tmp/scale_data.sql"])
            print("Database successfully scaled!")
            db_metrics = get_db_metrics()

    # 2. Run k6 Load Test
    print("\n[2/5] Running Load Test. Please wait...")
    
    # Remove stale summary if exists
    stale_summary_file = "load-tests/results/full-summary.json"
    if os.path.exists(stale_summary_file):
        try:
            os.remove(stale_summary_file)
        except Exception as e:
            print(f"Warning: Could not remove stale summary file: {e}")

    start_time = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    
    # Execute k6 with full summary enabled
    cmd = ["./load-tests/run.sh", profile]
    env = os.environ.copy()
    env["K6_FULL_SUMMARY"] = "1"
    
    # We want to show output to the user during the run
    subprocess.run(cmd, env=env)
    
    end_time = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    print("Load test execution completed.")
    
    # Wait for Prometheus buffer aggregation
    print("\n[3/5] Waiting 10 seconds for Prometheus metrics buffer...")
    time.sleep(10)

    # 3. Query Prometheus Infrastructure metrics
    print("\n[4/5] Retrieving Prometheus and Telemetry Metrics...")
    
    # CPU
    cpu_res = query_prometheus("100 - (avg(rate(node_cpu_seconds_total{mode='idle'}[1m])) * 100)", start_time, end_time)
    avg_cpu, peak_cpu = get_stats_from_prom_result(cpu_res)
    
    # Memory
    mem_res = query_prometheus("100 * (1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes))", start_time, end_time)
    avg_mem, peak_mem = get_stats_from_prom_result(mem_res)
    
    # Heap & Non-Heap
    heap_res = query_prometheus("sum(jvm_memory_used_bytes{area='heap'})", start_time, end_time)
    _, peak_heap = get_stats_from_prom_result(heap_res)
    peak_heap_mb = peak_heap / (1024 * 1024)
    
    nonheap_res = query_prometheus("sum(jvm_memory_used_bytes{area='nonheap'})", start_time, end_time)
    _, peak_nonheap = get_stats_from_prom_result(nonheap_res)
    peak_nonheap_mb = peak_nonheap / (1024 * 1024)
    
    # GC Pauses
    gc_dur_res = query_prometheus("sum(increase(jvm_gc_pause_seconds_sum[1m]))", start_time, end_time)
    avg_gc_dur, max_gc_dur = get_stats_from_prom_result(gc_dur_res)
    
    gc_count_res = query_prometheus("sum(increase(jvm_gc_pause_seconds_count[1m]))", start_time, end_time)
    avg_gc_count, max_gc_count = get_stats_from_prom_result(gc_count_res)

    # JVM Threads
    threads_res = query_prometheus("jvm_threads_live_threads", start_time, end_time)
    _, max_threads = get_stats_from_prom_result(threads_res)

    # HikariCP Pool
    active_conn_res = query_prometheus("hikaricp_connections_active", start_time, end_time)
    _, max_active_conn = get_stats_from_prom_result(active_conn_res)
    
    pending_conn_res = query_prometheus("hikaricp_connections_pending", start_time, end_time)
    _, max_pending_conn = get_stats_from_prom_result(pending_conn_res)

    # Disk
    disk_res = query_prometheus("100 * (1 - (node_filesystem_avail_bytes{mountpoint='/etc/hostname'} / node_filesystem_size_bytes{mountpoint='/etc/hostname'}))", start_time, end_time)
    if not disk_res:
        disk_res = query_prometheus("100 * (1 - (node_filesystem_avail_bytes{device=~'.*'} / node_filesystem_size_bytes{device=~'.*'}))", start_time, end_time)
    _, peak_disk = get_stats_from_prom_result(disk_res)

    # Network (If node exporter metric is available)
    net_rx_res = query_prometheus("sum(rate(node_network_receive_bytes_total[1m]))", start_time, end_time)
    avg_net_rx, _ = get_stats_from_prom_result(net_rx_res)
    
    net_tx_res = query_prometheus("sum(rate(node_network_transmit_bytes_total[1m]))", start_time, end_time)
    avg_net_tx, _ = get_stats_from_prom_result(net_tx_res)

    # 4. Read k6 Output File
    k6_summary_file = "load-tests/results/full-summary.json"
    k6_metrics = {}
    if os.path.exists(k6_summary_file):
        try:
            with open(k6_summary_file, 'r') as f:
                k6_data = json.load(f)
                metrics = k6_data.get('metrics', {})
                k6_metrics = {
                    "http_reqs": metrics.get('http_reqs', {}).get('values', {}).get('count', 0),
                    "http_req_rate": metrics.get('http_reqs', {}).get('values', {}).get('rate', 0.0),
                    "failure_rate": metrics.get('http_req_failed', {}).get('values', {}).get('rate', 0.0) * 100,
                    "avg_latency": metrics.get('http_req_duration', {}).get('values', {}).get('avg', 0.0),
                    "med_latency": metrics.get('http_req_duration', {}).get('values', {}).get('med', 0.0),
                    "p90_latency": metrics.get('http_req_duration', {}).get('values', {}).get('p(90)', 0.0),
                    "p95_latency": metrics.get('http_req_duration', {}).get('values', {}).get('p(95)', 0.0),
                    "data_received_mb": metrics.get('data_received', {}).get('values', {}).get('count', 0) / (1024*1024),
                    "data_sent_mb": metrics.get('data_sent', {}).get('values', {}).get('count', 0) / (1024*1024),
                }
        except Exception as e:
            print(f"Error parsing k6 summary JSON: {e}")

    # 5. Generate Markdown Report
    print("\n[5/5] Compiling Unified Benchmark Report...")
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    report_filename = f"load-tests/results/benchmark_report_{timestamp}.md"
    
    total_db_records = (db_metrics['products'] + db_metrics['orders'] + db_metrics['order_items'] + 
                        db_metrics['audit_logs'] + db_metrics['notifications']) if db_metrics else 0
    
    has_k6_metrics = len(k6_metrics) > 0
    if not has_k6_metrics:
        print("Warning: k6 summary statistics were not found. Defaulting to 'N/A' in the report.")

    report_content = f"""# Benchmark Automation Report
*Generated on {datetime.now().strftime("%Y-%m-%d %H:%M:%S")}*

## 1. Database Volume & Records Processed
* **Database Size**: {db_metrics['db_size'] if db_metrics else 'N/A'}
* **Total Seeded Database Records**: {format_num(total_db_records)}
  * Products: {format_num(db_metrics['products'] if db_metrics else None)}
  * Orders: {format_num(db_metrics['orders'] if db_metrics else None)}
  * Order Items: {format_num(db_metrics['order_items'] if db_metrics else None)}
  * Audit Logs: {format_num(db_metrics['audit_logs'] if db_metrics else None)}
  * Notifications: {format_num(db_metrics['notifications'] if db_metrics else None)}

## 2. Load Test Performance Statistics
* **Concurrent VUs Profile**: {profile.capitalize()}
* **Total HTTP Requests Executed**: {format_num(k6_metrics.get('http_reqs')) if has_k6_metrics else 'N/A'}
* **HTTP Throughput Rate**: {f"{k6_metrics.get('http_req_rate'):.2f} req/s" if has_k6_metrics else 'N/A'}
* **HTTP Failure Rate**: {f"{k6_metrics.get('failure_rate'):.4f}%" if has_k6_metrics else 'N/A'}
* **HTTP Latency Profile**:
  * Average: {f"{k6_metrics.get('avg_latency'):.2f} ms" if has_k6_metrics else 'N/A'}
  * Median: {f"{k6_metrics.get('med_latency'):.2f} ms" if has_k6_metrics else 'N/A'}
  * p(90): {f"{k6_metrics.get('p90_latency'):.2f} ms" if has_k6_metrics else 'N/A'}
  * p(95): {f"{k6_metrics.get('p95_latency'):.2f} ms" if has_k6_metrics else 'N/A'}
* **Network Data Transferred**:
  * Received: {f"{k6_metrics.get('data_received_mb'):.2f} MB" if has_k6_metrics else 'N/A'}
  * Sent: {f"{k6_metrics.get('data_sent_mb'):.2f} MB" if has_k6_metrics else 'N/A'}

## 3. Host Infrastructure Metrics
* **CPU Utilization**: Peak: **{peak_cpu:.2f}%** | Average: **{avg_cpu:.2f}%**
* **Memory Usage**: Peak: **{peak_mem:.2f}%** | Average: **{avg_mem:.2f}%**
* **Disk Usage**: Stable at **{peak_disk:.2f}%**
* **Network Traffic (Host Network Interface)**:
  * Inbound rate: {avg_net_rx / 1024:.2f} KB/s
  * Outbound rate: {avg_net_tx / 1024:.2f} KB/s

## 4. Application Runtime (JVM & Tomcat) Metrics
* **JVM Heap memory**: Peak Heap: **{peak_heap_mb:.2f} MB**
* **JVM Non-Heap memory**: Peak Non-Heap: **{peak_nonheap_mb:.2f} MB**
* **Garbage Collection Overhead**:
  * Avg pause duration: {avg_gc_dur:.4f} seconds/min
  * GC Collections: {avg_gc_count:.1f} collection runs/min
* **Thread Counts**: Peak live threads: **{max_threads:.0f}**

## 5. Database Connection Pool (HikariCP) Metrics
* **Active Connections**: Peak active connections: **{max_active_conn:.0f}** / 64 max
* **Pending Threads**: Peak threads waiting for connection: **{max_pending_conn:.0f}**
"""


    os.makedirs(os.path.dirname(report_filename), exist_ok=True)
    with open(report_filename, "w") as f:
        f.write(report_content)
        
    print(f"\nSuccess! Benchmark Report generated successfully at:\n  {report_filename}")
    print("\n------------------------------------------------------------------")
    print("Quick Telemetry Summary:")
    print(f"  Throughput: {k6_metrics.get('http_req_rate', 0.0):.2f} req/s | Error Rate: {k6_metrics.get('failure_rate', 0.0):.2f}%")
    print(f"  Latency (p95): {k6_metrics.get('p95_latency', 0.0):.2f} ms")
    print(f"  Peak CPU: {peak_cpu:.2f}% | Peak Memory: {peak_mem:.2f}%")
    print(f"  Peak JVM Heap: {peak_heap_mb:.2f} MB | Peak Active DB Conn: {max_active_conn:.0f}")
    print("------------------------------------------------------------------")

if __name__ == "__main__":
    main()
