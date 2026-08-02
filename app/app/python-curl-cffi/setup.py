from setuptools import find_packages, setup

setup(
    name="curl_cffi",
    version="0.7.0",
    description="Pure-Python requests-based shim of curl_cffi for Android "
    "(Chaquopy). TLS impersonation is unavailable on Android, so the "
    "impersonate profile is stored but ignored and all HTTP goes through "
    "the requests library.",
    packages=find_packages(),
    python_requires=">=3.8",
    install_requires=["requests"],
)
