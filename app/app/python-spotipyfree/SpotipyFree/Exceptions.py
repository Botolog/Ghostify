class SpotifyBaseException(Exception):
    pass


class SpotifyException(SpotifyBaseException):

    def __init__(self, *args, **kwargs):
        self.__dict__.update(kwargs)
        super().__init__(*args, **kwargs)


class SpotifyOauthError(SpotifyBaseException):
    def __init__(self, *args, **kwargs):
        self.__dict__.update(kwargs)
        super().__init__(*args, **kwargs)


class SpotifyStateError(SpotifyOauthError):
    def __init__(self, *args, **kwargs):
        self.__dict__.update(kwargs)
        super(SpotifyOauthError, self).__init__(*args, **kwargs)
