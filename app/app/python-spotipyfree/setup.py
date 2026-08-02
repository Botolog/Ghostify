from setuptools import setup

setup(
    name="spotipyfree",
    version="1.9.13",
    description="Spotipy-compatible anonymous wrapper over the SpotAPI "
    "private API (Android/Chaquopy build). The PyPI package hard-requires "
    "pymongo (no Android wheel); this vendored copy drops it and relies on "
    "the vendored spotapi package.",
    packages=["SpotipyFree"],
    python_requires=">=3.9",
    install_requires=["spotapi<2.0"],
)
