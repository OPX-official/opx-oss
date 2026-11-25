"""``platformdirs`` stand-in: the APK ships no pip packages.

wifi4's ``persist/config.py`` asks for one thing — ``user_config_dir("wifi4",
appauthor=False)`` — to place ``config.toml``. On Android there is no XDG home, so the
directory is resolved like the rest of the app's data: next to the app's files, or wherever
``XDG_CONFIG_HOME``/``OPX_HOME`` points (the desktop test harness). The value is only used as
a path, never as a decision, so a plain directory is all this needs to return.
"""

from __future__ import annotations

import os


def _base(env: str, fallback: str) -> str:
    return os.environ.get(env) or os.path.join(os.path.expanduser("~"), fallback)


def user_config_dir(appname: str = "", appauthor: str | None = None, **_kwargs) -> str:
    return os.path.join(_base("XDG_CONFIG_HOME", ".config"), appname) if appname \
        else _base("XDG_CONFIG_HOME", ".config")


def user_data_dir(appname: str = "", appauthor: str | None = None, **_kwargs) -> str:
    return os.path.join(_base("XDG_DATA_HOME", ".local/share"), appname) if appname \
        else _base("XDG_DATA_HOME", ".local/share")


def user_cache_dir(appname: str = "", appauthor: str | None = None, **_kwargs) -> str:
    return os.path.join(_base("XDG_CACHE_HOME", ".cache"), appname) if appname \
        else _base("XDG_CACHE_HOME", ".cache")


def user_log_dir(appname: str = "", appauthor: str | None = None, **_kwargs) -> str:
    return os.path.join(user_cache_dir(appname), "log")
