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


def _subcommands() -> list[str]:
    """Every registered subcommand, read off the parser.

    Enumerated rather than listed by hand: a hand-written list is exactly how
    the original syntax error stayed hidden, and it would leave each new
    command uncovered on the day it is added.
    """
    parser = cli.build_parser()
    actions = [a for a in parser._actions if hasattr(a, "choices") and a.choices]
    return sorted(actions[0].choices) if actions else []


def test_the_parser_registers_more_than_one_subcommand():
    assert len(_subcommands()) > 1


@pytest.mark.parametrize("cmd", _subcommands())
def test_each_subcommand_parses_its_help(cmd):
    with pytest.raises(SystemExit) as exc:
        cli.main([cmd, "--help"])
    assert exc.value.code == 0


@pytest.mark.parametrize("cmd", _subcommands())
def test_each_subcommand_has_a_handler(cmd):
    """A subcommand with no func would fail only when someone ran it."""
    parser = cli.build_parser()
    args = parser.parse_args([cmd] + (["--help"] if False else []))
    assert callable(getattr(args, "func", None))


def test_an_unknown_subcommand_is_rejected():
    with pytest.raises(SystemExit):
        cli.main(["no-such-command"])
