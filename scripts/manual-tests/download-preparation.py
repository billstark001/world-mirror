import argparse
import itertools
import json
from pathlib import Path
import time
from urllib.parse import urlsplit
from http.client import HTTPConnection
from uuid import uuid4


def wait_until(check, description, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.1)
    raise RuntimeError(f"Timed out waiting for {description}")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def verify(report_dir):
    connection_file = report_dir / "connection.json"
    wait_until(connection_file.is_file, "MineDriver connection file", 120)
    connection = json.loads(connection_file.read_text(encoding="utf-8"))
    endpoint = urlsplit(connection["endpoint"])
    require(endpoint.scheme == "http" and endpoint.hostname == "127.0.0.1" and endpoint.port,
            "Expected a loopback MineDriver session")
    require(connection["runId"] == report_dir.name, "MineDriver report directory does not match the run")
    ids = itertools.count(1)

    def call(method, **params):
        request_id = next(ids)
        client = HTTPConnection(endpoint.hostname, endpoint.port, timeout=100)
        try:
            client.request("POST", "/rpc",
                           body=json.dumps({"jsonrpc": "2.0", "id": request_id,
                                            "method": method, "params": params}).encode("utf-8"),
                           headers={"Authorization": "Bearer " + connection["token"],
                                    "Content-Type": "application/json"})
            with client.getresponse() as response:
                require(response.status == 200, f"MineDriver HTTP {response.status}: {method}")
                reply = json.load(response)
        finally:
            client.close()
        require(reply.get("id") == request_id and "result" in reply, f"MineDriver command failed: {method}")
        return reply["result"]

    def ready():
        status = call("agent.status")
        require(status["state"] not in {"FAILED", "FINISHED"}, "MineDriver session ended before the test")
        return status["state"] == "READY"

    wait_until(ready, "MineDriver READY", 120)
    call("session.commands")
    initial = call("session.inspect")
    require(initial["minecraft"] == "26.3" and initial["language"] == "en_us",
            "Run this check with Minecraft 26.3 and the English (US) interface")
    require(initial.get("development") and initial.get("loader") == "fabric"
            and (initial.get("screen") or "").endswith("TitleScreen") and not initial.get("inWorld"),
            "Use an isolated Fabric development session at the title screen")

    def key(name):
        call("input.key", key=name)

    def screen():
        return call("session.inspect").get("screen")

    def snapshot():
        return call("ui.snapshot")

    def button(translation_key, tab=False):
        current = snapshot()
        matches = [
            part for part in current["elements"]
            if part.get("key") == translation_key and (not tab or "Tab" in part.get("type", ""))
            and part.get("enabled", True) and part.get("visible", True) and part.get("hitBounds")
        ]
        require(len(matches) == 1, f"Expected one active widget for {translation_key}")
        bounds = matches[0]["hitBounds"]
        result = call("input.click", screenId=current["screenId"],
                      x=bounds["x"] + bounds["width"] / 2, y=bounds["y"] + bounds["height"] / 2)
        require(result["consumed"], f"Widget did not handle the click: {translation_key}")
        call("render.await", frames=3)

    def shows_text(text):
        return any(draw.get("text") == text for draw in snapshot()["render"]["draws"])

    log = report_dir / "client.log"
    offset = log.stat().st_size

    def log_contains(text):
        with log.open("rb") as stream:
            stream.seek(offset)
            return text.encode("utf-8") in stream.read()

    lease = call("input.isolation.acquire", ttlMillis=240_000)["leaseId"]
    try:
        call("world.create", name=f"Download preparation {uuid4().hex[:8]}", seed="42",
             mode="CREATIVE", flat=True, timeoutMillis=90_000)
        wait_until(lambda: call("session.inspect").get("gameplayReady"), "singleplayer readiness")

        key("U")
        key("U")
        wait_until(lambda: log_contains("Download preparation cancelled reason=manual-toggle"),
                   "the second press to cancel preparation", 12)
        require(not screen(), "Cancelling preparation opened a screen")
        call("render.await", frames=100)
        require(not log_contains("Download activated pipeline="), "Capture activated after cancellation")
        require(not screen(), "Cancelled preparation opened a delayed screen")

        key("U")
        wait_until(lambda: log_contains("Download activated pipeline="), "capture activation after retry", 55)
        require(not screen(), "Starting a download opened a screen")
        key("U")
        wait_until(lambda: log_contains("Download stopped reason=manual-toggle"), "capture stop")

        key("I")
        wait_until(lambda: (screen() or "").endswith("StatusScreen"), "in-game status screen")
        button("screen.worldmirror.tab.settings", tab=True)
        button("screen.worldmirror.status.saveLoc")
        require(shows_text("Saves Folder"), "The World Location popup did not open")
        key("ESCAPE")
        require(not shows_text("Saves Folder"), "Escape did not dismiss the World Location popup")
        button("screen.worldmirror.routing.title")
        wait_until(lambda: (screen() or "").endswith("WorldRoutingScreen"), "in-game routes")
        wait_until(lambda: any(part.get("key") == "gui.done" and part.get("enabled", True)
                               for part in snapshot()["elements"]), "route refresh to finish")
        button("gui.done")
        require((screen() or "").endswith("StatusScreen"), "Routes did not return to the status screen")
        button("gui.done")
        call("world.disconnect")
    finally:
        call("input.isolation.release", leaseId=lease)

    print("Download preparation cancellation, retry and in-game routing return passed.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Verify download preparation in an isolated MineDriver client.")
    parser.add_argument("report_directory", type=Path, help="The current mineDriverRun report directory")
    verify(parser.parse_args().report_directory)
