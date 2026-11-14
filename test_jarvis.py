"""Run: uv run python test_jarvis.py"""
from jarvis import Voice, clean

assert clean("**Done**, see https://x.com/a") == "Done, see link"
assert clean("- item one") == "item one"

v = Voice("Daniel", "Milena", 195, mute=True)
said = []
v.say = said.append  # capture instead of speaking
v.feed("Hello there, sir. The time is 3.5 hours")
assert said == ["Hello there, sir."], said
v.feed(" past noon!\nNext")
v.flush()
assert said == ["Hello there, sir.", "The time is 3.5 hours past noon!", "Next"], said
print("ok")
