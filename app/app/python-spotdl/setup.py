from setuptools import find_packages, setup

setup(
    name="spotdl",
    version="4.5.2",
    description="Download Spotify playlists from YouTube (Android/Chaquopy build). "
    "Vendored copy with the web-UI subcommand (fastapi/uvicorn/pydantic) removed "
    "because pydantic-core has no Android wheel; this app only uses the "
    "metadata-fetch and single-track download paths.",
    packages=find_packages(),
    python_requires=">=3.8",
    install_requires=[
        "beautifulsoup4<5,>=4.12.3",
        "mutagen<2,>=1.47.0",
        "platformdirs<5,>=4.3.6",
        "pykakasi<3,>=2.3.0",
        "python-slugify[unidecode]<9,>=8.0.4",
        "rapidfuzz<4,>=3.10.1",
        "requests<3,>=2.32.3",
        "rich<14,>=13.9.4",
        "soundcloud-v2<2,>=1.6.0",
        "spotipy<3,>=2.26.0",
        "spotipyfree<2,>=1.9.13",
        "syncedlyrics<2,>=1.0.1",
        "yt-dlp<2027,>=2026.07.04",
        "ytmusicapi<2,>=1.12.1",
    ],
)
