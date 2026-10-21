#!/usr/bin/env python3
"""Compatibility entry point; maintained implementation: checks/verify_69_ac.py."""
from pathlib import Path
import runpy

if __name__ == "__main__":
    runpy.run_path(str(Path(__file__).resolve().parent / "checks" / "verify_69_ac.py"), run_name="__main__")
