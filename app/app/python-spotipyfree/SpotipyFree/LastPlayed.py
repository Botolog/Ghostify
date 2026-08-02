import time
from spotapi.status import PlayerStatus
import datetime
import threading
import traceback


class LastPlayedManger:
    def __init__(self, login):
        self.thread = None
        self.run = False
        self.login = login
        self.manager = PlayerStatus(login)
        self.lastPLayed = ""
        self.lastTrackUri = None
        self.lastPlayedAt = None
        self.lastContextUri = None

    def updateLoop(self, callback, refreshInterval=3):
        while self.run:
            try:
                state = self.manager.state  #< For some reason SpotAPI makes this property a function which when called often gets rate limited...
                timestamp = int(state.timestamp) / 1000
                if self.lastPLayed != state.track.uid:
                    if self.lastTrackUri != None:
                        timePlayed = max(0, int((time.time() - self.lastPlayedAt.timestamp()) * 1000))
                        callback(self.lastTrackUri, self.lastPlayedAtText, self.lastContextUri, timePlayed)
                    self.lastTrackUri = state.track.uri
                    self.lastPlayedAt = (
                        datetime.datetime.fromtimestamp(
                            timestamp, tz=datetime.timezone.utc
                        )
                    )
                    self.lastPlayedAtText = self.lastPlayedAt.isoformat().replace("+00:00", "Z")
                    self.lastContextUri = state.context_uri
                    self.lastPLayed = state.track.uid
                time.sleep(refreshInterval)
            except Exception as e:
                print(f"[SpotipyFree] Error in Recently Played: {e}")
                traceback.print_exc()
                time.sleep(10)
                try:
                    self.manager.reconnect()
                except Exception as e:
                    print(f"[SpotipyFree] Listener stopped due to websocket disconnection. To reconnect, you must use run pip uninstall spotAPI and then pip install git+https://github.com/TzurSoffer/SpotAPI/")
                    traceback.print_exc()

    def start(self, callback, refreshInterval):
        self.run = True
        self.thread = threading.Thread(target=self.updateLoop, args=(callback, refreshInterval))
        self.thread.daemon = True
        self.thread.start()

    def stop(self):
        self.run = False
        self.thread.join()


if __name__ == "__main__":

    import SpotipyFree

    sp = SpotipyFree.Spotify()
    sp.login()
