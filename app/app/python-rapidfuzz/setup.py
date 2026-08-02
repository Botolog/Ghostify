from setuptools import find_packages, setup

setup(
    name="rapidfuzz",
    version="3.10.1",
    description="Pure-Python fallback of rapidfuzz (Android/Chaquopy). "
    "Ships the subset of the API used by spotdl, implemented with difflib.",
    packages=find_packages(),
    python_requires=">=3.8",
)
