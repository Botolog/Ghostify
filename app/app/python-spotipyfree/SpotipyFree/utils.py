import os
import platform
from pathlib import Path


def getConfigFolder() -> Path:
    """
    Get the path to the config folder
    """

    if platform.system() == "Linux":
        configPath = Path.home() / ".config" / "spotipyFree"
        if configPath.exists():
            return configPath

        os.makedirs(configPath, exist_ok=True)
        return configPath

    configPath = Path.home() / ".spotipyFree"
    os.makedirs(configPath, exist_ok=True)

    return configPath


def getCookiesFile(cookiesFile=None) -> str:
    """
    Get the path to the cookies file
    """

    if cookiesFile == None:
        configFolder = getConfigFolder()
        cookiesFile = configFolder / "cookies.json"

    if not cookiesFile.exists():
        with open(cookiesFile, "w") as f:
            f.write("{}")

    return str(cookiesFile)
