from pathlib import Path
import json,sys,datetime
G=Path(__file__).resolve().parent
s=json.loads((G/"manual-status.json").read_text(encoding="utf-8"));action=sys.argv[1]
if action=="status":print(json.dumps(s,ensure_ascii=False,indent=2));raise SystemExit(0)
if s["state"]!="READY_FOR_REOPEN_OR_FINISH":print("Already running or not ready. No second instance started.");raise SystemExit(2)
if action not in ["launch","finish"]:raise SystemExit(3)
with (G/"control"/(action+".json")).open("x",encoding="utf-8") as f:json.dump({"utc":datetime.datetime.now(datetime.timezone.utc).isoformat(),"source":"manual control"},f)
print("Requested "+action+" for isolated T2 only.")
