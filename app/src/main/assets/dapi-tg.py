#!/usr/bin/env python3
"""
dapi-tg - run Ubuntu Terminal commands from a Telegram chat. NO root needed.

Control your phone's Ubuntu from anywhere on the internet: every message you
send to your own bot is executed in bash and the output comes back as a reply.

One-time setup (inside Ubuntu):
  1) apt install -y python3
  2) in Telegram talk to @BotFather -> /newbot -> copy the token
  3) dapi tg 123456:ABC-DEF...        (or: python3 /usr/local/bin/dapi-tg TOKEN)
  4) open your bot's chat and send /start -> it locks to YOUR chat only

Then just type commands in the chat, e.g.:  ls -l /  •  df -h  •  dapi battery

  dapi tg TOKEN [CHAT_ID]   run the bot (Ctrl+C to stop)
  dapi tg --reset           unlink the locked chat

Only the locked chat is served - strangers are ignored. Keep the app open on
the phone (Android kills background apps - disable battery optimization for it).
"""
import json
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request

LOCK = os.path.expanduser("~/.dapi-tg-chat")
HOME = os.path.expanduser("~")

HELP = (
    "dapi-tg - remote shell for Ubuntu Terminal\n"
    "Send any text and it runs in bash on the phone.\n"
    "Commands:\n"
    "  /help - this help\n"
    "  /id   - show this chat id\n"
    "Anything else = a shell command (90s timeout).\n"
    "Examples: dapi battery | df -h | dapi flash list"
)


def call(token, method, **params):
    url = "https://api.telegram.org/bot" + token + "/" + method
    data = urllib.parse.urlencode(params).encode()
    req = urllib.request.Request(url, data=data)
    with urllib.request.urlopen(req, timeout=35) as r:
        return json.load(r)


def send(token, chat, text):
    text = (text or "(no output)")[:4000]
    for _ in range(3):
        try:
            call(token, "sendMessage", chat_id=chat, text=text)
            return
        except Exception:
            time.sleep(2)


def run_cmd(text):
    try:
        p = subprocess.run(
            ["bash", "-c", text], capture_output=True, timeout=90, cwd=HOME
        )
        out = p.stdout.decode("utf-8", "replace") + p.stderr.decode("utf-8", "replace")
        return out.strip() or "(no output)"
    except subprocess.TimeoutExpired:
        return "(timeout after 90s)"
    except Exception as e:  # noqa: BLE001
        return "(error: %s)" % e


def main():
    args = sys.argv[1:]
    if args and args[0] in ("--reset", "-r"):
        try:
            os.remove(LOCK)
        except OSError:
            pass
        print("chat lock cleared")
        return
    if not args:
        print(__doc__)
        return
    token = args[0]
    want = args[1] if len(args) > 1 else ""
    try:
        me = call(token, "getMe")
        if not me.get("ok"):
            raise RuntimeError(str(me))
    except Exception as e:  # noqa: BLE001
        print("token check failed:", e)
        print("get a token from @BotFather (/newbot)")
        return
    chat = want or (open(LOCK).read().strip() if os.path.exists(LOCK) else "")
    print("bot @%s running - send /start from Telegram (Ctrl+C to stop)"
          % me["result"].get("username", "?"))
    offset = 0
    while True:
        try:
            upd = call(token, "getUpdates", offset=offset + 1, timeout=25,
                       allowed_updates='["message"]')
        except Exception:
            time.sleep(4)
            continue
        for u in upd.get("result", []):
            offset = max(offset, u["update_id"])
            msg = u.get("message") or {}
            cid = str((msg.get("chat") or {}).get("id", ""))
            text = (msg.get("text") or "").strip()
            if not cid or not text:
                continue
            if not chat:
                with open(LOCK, "w") as f:
                    f.write(cid)
                chat = cid
                send(token, cid, "linked! this bot now answers only this chat. /help for help")
                print("linked to chat", cid)
                continue
            if cid != chat:
                continue
            if text in ("/start", "/help"):
                send(token, cid, HELP)
            elif text == "/id":
                send(token, cid, "chat id: " + cid)
            else:
                print("cmd:", text[:120].replace("\n", " "))
                send(token, cid, "$ " + text + "\n" + run_cmd(text))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nstopped")
