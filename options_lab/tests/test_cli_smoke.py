"""The CLI must at least import and build its parser.

Added after a syntax error in cli.py survived a fully green suite: nothing
imported the module, so 218 passing tests said nothing about whether the
entry point ran at all.
"""
from __future__ import annotations

import pytest

from options_lab import cli


def test_the_cli_module_imports():
    assert hasattr(cli, "main")


def test_the_parser_builds_and_knows_both_commands():
    with pytest.raises(SystemExit):
        cli.main(["--help"])


@pytest.mark.parametrize("cmd", ["ic", "expiry-put"])
def test_each_subcommand_parses_its_help(cmd):
    with pytest.raises(SystemExit) as exc:
        cli.main([cmd, "--help"])
    assert exc.value.code == 0


def test_an_unknown_subcommand_is_rejected():
    with pytest.raises(SystemExit):
        cli.main(["no-such-command"])
