"""Require exactly one executed JUnit method; preserve the original XML separately."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

def verify(directory, classname, method):
    files = sorted(Path(directory).rglob('TEST-*.xml'))
    assert files, 'NO_ORIGINAL_JUNIT_XML'
    cases = []
    for path in files:
        cases.extend(ET.parse(path).getroot().iter('testcase'))
    assert len(cases) == 1, ('METHOD_COUNT', len(cases))
    case = cases[0]
    assert case.attrib['classname'] == classname and case.attrib['name'] == method
    assert not any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped'))
    print('One original JUnit method executed successfully: '+classname+'.'+method)

if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('directory'); parser.add_argument('classname'); parser.add_argument('method')
    args=parser.parse_args(); verify(args.directory,args.classname,args.method)
