from pathlib import Path
import argparse,hashlib,json
p=argparse.ArgumentParser();p.add_argument('--package');p.add_argument('--cache');p.add_argument('--proxy-routes');p.add_argument('--download',action='store_true');p.add_argument('--expected-version')
args=p.parse_args();value=args.proxy_routes
result=dict(kind='SYNTHETIC_RECEIVED_ARGV_ONLY',routeLength=len(value),routeSha256=hashlib.sha256(value.encode()).hexdigest(),doubleQuotes=value.count('"'))
try:
    decoded=json.loads(value);result.update(jsonParsed=True,destinationCount=len(decoded))
except json.JSONDecodeError as error:result.update(jsonParsed=False,errorType=type(error).__name__,position=error.pos)
print(json.dumps(result))
