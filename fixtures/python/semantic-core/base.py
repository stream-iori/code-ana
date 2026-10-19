class Base:
    def run(self):
        return 1

    @staticmethod
    def static():
        return 2

    @classmethod
    def create(cls):
        return cls()
