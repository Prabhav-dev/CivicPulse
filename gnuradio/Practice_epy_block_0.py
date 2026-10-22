import os
import queue
import threading
import time
import numpy as np
from gnuradio import gr
import requests

class civicpulse_http_sink(gr.sync_block):
    """
    CivicPulse HTTP Telemetry Sink
    Calculates average power (dB) and posts JSON to CivicPulse asynchronously.
    Supports Render deployments and suppressed connection timeouts.
    """
    def __init__(self, url="http://127.0.0.1:7070/ingest", mac="AA:BB:CC:DD:EE:FF", node_type="utilityline"):
        gr.sync_block.__init__(
            self,
            name='CivicPulse HTTP Sink',
            in_sig=[np.float32],  # Accepts float stream from your filters/moving average
            out_sig=None           # Sink block (no output stream)
        )
        if "[" in url and "](" in url:
            url = url.split("](")[1].rstrip(")")
        target_url = url.strip().strip("'\"")

        # Automatically resolve environment variable if deployed on Render or configured externally
        env_url = (os.environ.get("CIVICPULSE_URL") or 
                   os.environ.get("CIVICPULSE_INGEST_URL") or 
                   os.environ.get("RENDER_EXTERNAL_URL") or 
                   os.environ.get("RENDER_URL"))
        if env_url:
            env_url = env_url.strip().strip("'\"")
            if not env_url.endswith("/ingest"):
                env_url = env_url.rstrip("/") + "/ingest"
            target_url = env_url

        self.url = target_url
        self.mac = mac.strip().strip("'\"")
        self.node_type = node_type.strip().strip("'\"")
        self.api_key = os.environ.get("CIVICPULSE_API_KEY", "").strip()
        self.last_send_time = 0.0

        # Background worker queue to prevent blocking the DSP work() loop
        self.queue = queue.Queue(maxsize=2)
        self.running = True
        self.connected = False
        self.last_warning_time = 0.0

        self.worker = threading.Thread(target=self._dispatch_loop, name="civicpulse-async-poster", daemon=True)
        self.worker.start()

    def work(self, input_items, output_items):
        in0 = input_items[0]
        current_time = time.time()
        
        # Enqueue telemetry once every 1.0 second to avoid flooding
        if len(in0) > 0 and (current_time - self.last_send_time) >= 1.0:
            power_rms = float(np.mean(in0**2))
            power_db = float(10 * np.log10(power_rms + 1e-12))

            payload = {
                "nodeType": self.node_type,
                "mac": self.mac,
                "rfPowerDb": round(power_db, 2),
                "ts": int(current_time * 1000)
            }

            try:
                # Discard stale if queue is full and put new reading
                if self.queue.full():
                    try:
                        self.queue.get_nowait()
                    except queue.Empty:
                        pass
                self.queue.put_nowait((payload, power_db))
            except queue.Full:
                pass

            self.last_send_time = current_time

        return len(in0)

    def _dispatch_loop(self):
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["X-API-Key"] = self.api_key

        while self.running:
            try:
                payload, power_db = self.queue.get(timeout=0.5)
            except queue.Empty:
                continue

            try:
                resp = requests.post(self.url, json=payload, headers=headers, timeout=1.5)
                if not self.connected:
                    print(f"[CivicPulse Sink] Connected successfully to {self.url} (HTTP {resp.status_code})")
                    self.connected = True
                else:
                    print(f"[CivicPulse Sink] Ingested RF Power: {round(power_db, 2)} dB -> HTTP {resp.status_code}")
            except (requests.exceptions.RequestException, Exception) as e:
                # Gracefully handle timeouts and connection refusals without spamming or throwing errors
                now = time.time()
                self.connected = False
                if now - self.last_warning_time >= 20.0:
                    print(f"[CivicPulse Sink Notice] Target {self.url} currently unreachable (offline or timeout). "
                          f"Telemetry will retry automatically in background. "
                          f"(Tip: Set CIVICPULSE_URL to your Render app URL e.g. https://<app>.onrender.com/ingest)")
                    self.last_warning_time = now

    def stop(self):
        self.running = False
        return True