PYTHON ?= python3

.PHONY: help check-docs
.DEFAULT_GOAL := help

help:
	@echo "make check-docs  Run read-only documentation, SRS scope and 69-AC checks"

check-docs:
	$(PYTHON) scripts/docs/checks/check_documentation.py
	$(PYTHON) scripts/docs/checks/verify_scoped_srs.py
	$(PYTHON) scripts/docs/checks/verify_69_ac.py
