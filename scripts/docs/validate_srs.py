#!/usr/bin/env python3
"""Compatibility entry point; maintained implementation: legacy/validate_srs.py."""
from pathlib import Path
import runpy

if __name__ == "__main__":
    runpy.run_path(str(Path(__file__).resolve().parent / "legacy" / "validate_srs.py"), run_name="__main__")
