"""Read-only cloud provenance checks; this module never reads signing identities."""
import argparse
import json
import os
from pathlib import Path
import urllib.request
from contract import BASE, artifact_metadata, load, require, validate_sign, write_new


def get(path):
    repository = os.environ['GITHUB_REPOSITORY']
    token = os.environ['GH_TOKEN']
    request = urllib.request.Request('https://api.github.com/repos/' + repository + '/actions/' + path,
        headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json'})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def check(expected):
    run = get('runs/' + str(expected['runId']))
    artifact = get('artifacts/' + str(expected['id']))
    require(run['conclusion'] == 'success', 'SOURCE_RUN_NOT_SUCCESS')
    require(run['head_sha'] == artifact['workflow_run']['head_sha'] == expected['controlSha'], 'CONTROL_SOURCE')
    require(artifact['workflow_run']['id'] == expected['runId'] and artifact['id'] == expected['id'], 'ARTIFACT_RUN')
    require(artifact['name'] == expected['name'] and not artifact['expired'], 'ARTIFACT_NAME_OR_EXPIRED')
    require(artifact['size_in_bytes'] == expected['bytes'], 'ARTIFACT_SIZE')
    require(artifact['digest'] == 'sha256:' + expected['outerSha256'], 'ARTIFACT_DIGEST')
    return {'runSha': run['head_sha'], 'artifact': artifact}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--sign-request', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.sign_request:
        expected = validate_sign(load(args.sign_request))['originalArtifact']
    else:
        expected = {'id': BASE['artifactId'], 'runId': BASE['runId'], 'name': BASE['artifactName'],
                    'bytes': BASE['outerBytes'], 'outerSha256': BASE['outerSha256'], 'controlSha': BASE['controlSha']}
    write_new(args.output, check(expected))
