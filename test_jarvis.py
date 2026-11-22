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
print("voice ok")

from phone import tool_args  # Claude may nest phone-tool args, flatten them, or send a JSON string
assert tool_args({"action": "contacts", "query": "Kate"}) == {"query": "Kate"}
assert tool_args({"action": "contacts", "args": {"query": "Kate"}}) == {"query": "Kate"}
assert tool_args({"action": "contacts", "args": '{"query": "Kate"}'}) == {"query": "Kate"}
assert tool_args({"action": "battery"}) == {}
print("phone ok")

from phone import looped  # Whisper hallucination loops are dropped, real requests are kept
assert looped("in the name of the name of the name of the name of the name of the name of")
assert not looped("What is the capital of Japan?")
assert not looped("Open Safari, go to the Apple website, find the newest MacBook and read me its price and battery life")
print("transcribe ok")
