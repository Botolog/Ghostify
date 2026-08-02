from setuptools import find_packages, setup

setup(
    name="spotapi",
    version="1.2.8",
    description="Wrapper for Spotify's private API (Android/Chaquopy build). "
    "Vendored copy stripped for on-device use: curl_cffi is replaced by a "
    "requests-based shim (python-curl-cffi), pymongo/redis/readerwriterlock "
    "savers are removed, and websockets/colorama are optional. Only pure-Python "
    "requirements are declared so pip can resolve them from PyPI.",
    packages=find_packages(),
    python_requires=">=3.8",
    install_requires=[
        "requests",
        "beautifulsoup4",
        "typing_extensions",
        "pyotp",
    ],
)
