"""Cloud calibration of actual Kotlin estimates against two fixed text tokenizers.

Reads only an explicitly provided synthetic test report, never a profile or chat file.
No production tokenizer dependency, provider request, billing or accuracy threshold.
"""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path

import tiktoken


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--pip-report', type=Path, required=True)
    args = parser.parse_args()
    source = args.input.read_bytes()
    data = json.loads(source)
    if data.get('schema') != 1 or data.get('kind') != 'SYNTHETIC_TEXT_TOKENIZER_CALIBRATION':
        raise ValueError('SYNTHETIC_CALIBRATION_REPORT_REQUIRED')
    if importlib.metadata.version('tiktoken') != '0.12.0':
        raise ValueError('PINNED_TOKENIZER_VERSION_REQUIRED')
    rows = []
    for name in ['cl100k_base', 'o200k_base']:
        # The pinned upstream encoding loader verifies each downloaded rank file's expected SHA.
        encoding = tiktoken.get_encoding(name)
        for sample in data['samples']:
            text = sample['text']
            encoded = encoding.encode_ordinary(text)
            if encoding.decode(encoded) != text:
                raise ValueError('TEXT_ROUNDTRIP_FAILED')
            reference = len(encoded)
            estimate = sample['estimatedTokens']
            rows.append(dict(sample=sample['id'], tokenizer=name, referenceTextTokens=reference,
                estimatedTextTokens=estimate, utf16Units=sample['utf16Units'],
                difference=estimate-reference,
                relativeDifference=(estimate-reference)/reference if reference else None))
    packages = json.loads(args.pip_report.read_text(encoding='utf-8'))
    dependencies = [dict(name=item['metadata']['name'], version=item['metadata']['version'],
        archiveHashes=item.get('download_info', {}).get('archive_info', {}).get('hashes', {}))
        for item in packages['install']]
    report = dict(kind='SCOPED_SYNTHETIC_TEXT_TOKENIZER_COMPARISON', schema=1,
        inputSha256=hashlib.sha256(source).hexdigest(), estimator=data['estimator'],
        tokenizerPackage='tiktoken', tokenizerVersion=importlib.metadata.version('tiktoken'),
        upstream='https://github.com/openai/tiktoken/tree/0.12.0',
        installedDependencies=dependencies, samples=len(data['samples']), rows=rows,
        providerCalls=False, realUserData=False, estimatorTuned=False,
        billingOrRequestEnvelopeValidated=False, cacheBillingValidated=False,
        multimodalCostsValidated=False, universalModelCompatibility=False)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print('SCOPED_TOKENIZER_COMPARISON_RECORDED samples=' + str(len(data['samples'])))


if __name__ == '__main__':
    main()
