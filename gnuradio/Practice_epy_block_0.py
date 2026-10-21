import numpy as np
from gnuradio import gr
import requests
import time

class civicpulse_http_sink(gr.sync_block):
    """
    CivicPulse HTTP Telemetry Sink
    Calculates average power (dB) and posts JSON to CivicPulse.
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
        self.url = url.strip().strip("'\"")
        self.mac = mac.strip().strip("'\"")
        self.node_type = node_type.strip().strip("'\"")
        self.last_send_time = 0.0

    def work(self, input_items, output_items):
        in0 = input_items[0]
        current_time = time.time()
        
        # Send telemetry once every 1.0 second to avoid flooding the backend
        if len(in0) > 0 and (current_time - self.last_send_time) >= 1.0:
            # Calculate average power in relative dB
            power_rms = float(np.mean(in0**2))
            power_db = float(10 * np.log10(power_rms + 1e-12))

            payload = {
                "nodeType": self.node_type,
                "mac": self.mac,
                "rfPowerDb": round(power_db, 2),
                "ts": int(current_time * 1000)
            }
            
            headers = {"Content-Type": "application/json"}
            
            try:
                # Non-blocking HTTP POST request to CivicPulse
                resp = requests.post(self.url, json=payload, headers=headers, timeout=0.8)
                print(f"[CivicPulse Sink] Ingested RF Power: {round(power_db, 2)} dB -> HTTP {resp.status_code}")
            except Exception as e:
                print(f"[CivicPulse Sink Warning] Could not post to {self.url}: {e}")
                
            self.last_send_time = current_time

        return len(in0)