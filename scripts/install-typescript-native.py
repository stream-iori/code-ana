#!/usr/bin/env python3
"""Verify then atomically select an immutable TypeScript SDK version."""
import argparse
import json
from pathlib import Path
from typescript_sdk import install
ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk',type=Path,required=True)
    parser.add_argument('--prefix',type=Path,default=ROOT/'target')
    parser.add_argument('--worker',type=Path,default=ROOT/'target/debug/codeana-typescript-worker')
    args=parser.parse_args()
    print(json.dumps(install(args.sdk,args.prefix,args.worker)))

if __name__=='__main__':main()
