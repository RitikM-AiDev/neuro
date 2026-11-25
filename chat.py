import os
import sys
from pathlib import Path
from google import genai
from google.genai import types


def load_api_key() -> str:
    env_path = Path(__file__).resolve().parent / ".env"
    if env_path.exists():
        for line in env_path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            key = key.strip()
            value = value.strip().strip('"').strip("'")
            if key in ("gemini_api_key", "GEMINI_API_KEY") and value:
                return value

    env_val = os.getenv("gemini_api_key") or os.getenv("GEMINI_API_KEY")
    if env_val:
        return env_val

    raise RuntimeError(
        "Missing gemini_api_key in .env or environment variables."
    )


def main() -> None:
    api_key = load_api_key()
    print("\n\n\n\n\n" +"API KEY -------------------------" + api_key + "\n\n\n\n\n")

    client = genai.Client(api_key=api_key)

    config = types.GenerateContentConfig(
        tools=[
            types.Tool(
                google_search=types.GoogleSearch(),
            )
        ]
    )

    if len(sys.argv) > 1:
        prompt = " ".join(sys.argv[1:])
        response = client.models.generate_content(
            model="gemini-3.5-flash-lite",
            contents=prompt,
            config=config,
        )
        print(response.text)
        return

    print("Gemini 3.8 Flash test chat (type 'exit' or 'quit' to stop)")
    while True:
        try:
            prompt = input("\nYou: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nExiting.")
            break

        if not prompt:
            continue
        if prompt.lower() in ("exit", "quit"):
            break

        try:
            response = client.models.generate_content(
                model="gemini-3.5-flash-lite",
                contents=prompt,
                config=config,
            )
            print(f"\nGemini: {response.text}")
        except Exception as exc:
            print(f"\nError: {exc}")


if __name__ == "__main__":
    main()
