from base import Base as Parent

class Child(Parent):
    def run(self):
        return super().run()

def invoke():
    return Child().run(), Parent.static(), Parent.create().run()

def nested():
    def run():
        return 3
    return run()
