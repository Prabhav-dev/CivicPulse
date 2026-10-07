import math
import json
import queue
import threading
import time
from urllib.error import URLError
from urllib.request import Request, urlopen

import numpy as np
from gnuradio import gr


class blk(gr.sync_block):
    """Send one second of mean complex-sample power as an RF-only reading."""

    def __init__(self, url="http://127.0.0.1:7070/ingest",
                 node_type="streetlight", mac="AA:BB:CC:DD:EE:FF",
                 sample_rate=32000):
        gr.sync_block.__init__(
            self,
            name="CivicPulse RF telemetry",
            in_sig=[np.complex64],
            out_sig=None,
        )
        self.url = url
        self.node_type = node_type
        self.mac = mac
        self.samples_per_second = max(1, int(sample_rate))
        self.samples_seen = 0
        self.power_sum = 0.0
        self.pending = queue.Queue(maxsize=1)
        self.stopping = threading.Event()
        self.sender = threading.Thread(target=self._send_loop, name="civicpulse-post", daemon=True)
        self.sender.start()

    def work(self, input_items, output_items):
        samples = input_items[0]
        offset = 0
        while offset < len(samples):
            take = min(self.samples_per_second - self.samples_seen, len(samples) - offset)
            chunk = samples[offset:offset + take]
            self.power_sum += float(np.sum(np.abs(chunk) ** 2))
            self.samples_seen += take
            offset += take
            if self.samples_seen == self.samples_per_second:
                mean_power = self.power_sum / self.samples_per_second
                power_db = 10.0 * math.log10(max(mean_power, 1.0e-12))
                try:
                    self.pending.put_nowait((power_db, int(time.time() * 1000)))
                except queue.Full:
                    pass
                self.samples_seen = 0
                self.power_sum = 0.0
        return len(samples)

    def _send_loop(self):
        while not self.stopping.is_set():
            try:
                power_db, timestamp = self.pending.get(timeout=0.2)
            except queue.Empty:
                continue
            payload = {
                "nodeType": self.node_type,
                "mac": self.mac,
                "rfPower": power_db,
                "ts": timestamp,
            }
            try:
                request = Request(self.url, data=json.dumps(payload).encode("utf-8"),
                                  headers={"Content-Type": "application/json"}, method="POST")
                with urlopen(request, timeout=1):
                    pass
            except (OSError, TimeoutError, URLError):
                pass

    def stop(self):
        self.stopping.set()
        self.sender.join(timeout=0.1)
        return True