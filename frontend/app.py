from flask import Flask, render_template, request, jsonify, Response
import requests
import os

app = Flask(__name__)
CONTROLLER = os.environ.get("CONTROLLER_URL", "http://localhost:8080").rstrip("/")
CONTROLLER_TOKEN = os.environ.get("CONTROLLER_TOKEN", "changeme-api-token")


def _proxy(method, url, **kwargs):
    try:
        resp = requests.request(method, url, timeout=120, **kwargs)
        try:
            return jsonify(resp.json()), resp.status_code
        except Exception:
            return Response(resp.content, status=resp.status_code,
                            content_type=resp.headers.get("Content-Type", "text/plain"))
    except requests.exceptions.ConnectionError:
        return jsonify({"error": f"Cannot connect to controller at {CONTROLLER}"}), 503
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/")
def index():
    return render_template("index.html", controller_url=CONTROLLER)


@app.route("/health")
def health():
    try:
        r = requests.get(f"{CONTROLLER}/", timeout=3)
        return jsonify({"status": "ok", "controller": CONTROLLER, "code": r.status_code})
    except Exception as e:
        return jsonify({"status": "error", "controller": CONTROLLER, "error": str(e)}), 503


@app.route("/api/<path:path>", methods=["GET", "POST", "DELETE", "PUT", "PATCH"])
def api_proxy(path):
    headers = {k: v for k, v in request.headers
               if k.lower() not in ("host", "content-length", "transfer-encoding")}
    headers["Authorization"] = f"Bearer {CONTROLLER_TOKEN}"
    return _proxy(
        request.method,
        f"{CONTROLLER}/api/{path}",
        headers=headers,
        json=request.get_json(silent=True),
        params=request.args,
    )


@app.route("/jnlpJars/<filename>")
def jar_download(filename):
    try:
        resp = requests.get(f"{CONTROLLER}/jnlpJars/{filename}", timeout=120)
        if resp.status_code != 200:
            return f"JAR not available (controller returned {resp.status_code})", resp.status_code
        return Response(
            resp.content,
            content_type="application/java-archive",
            headers={"Content-Disposition": f'attachment; filename="{filename}"'},
        )
    except Exception as e:
        return str(e), 503


@app.route("/computer/<name>/slave-agent.jnlp")
def jnlp(name):
    try:
        resp = requests.get(f"{CONTROLLER}/computer/{name}/slave-agent.jnlp", timeout=10)
        return Response(resp.content, status=resp.status_code,
                        content_type=resp.headers.get("Content-Type", "text/xml"))
    except Exception as e:
        return str(e), 503


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    # Bind to localhost only by default — set LISTEN_HOST=0.0.0.0 to expose externally (e.g. inside Docker)
    host = os.environ.get("LISTEN_HOST", "127.0.0.1")
    print(f"\n  Evil Jenkins UI  →  http://{host}:{port}")
    print(f"  Controller       →  {CONTROLLER}")
    if CONTROLLER_TOKEN == "changeme-api-token":
        print(f"  WARNING: Using default API token. Set CONTROLLER_TOKEN env var to match api.token in application.yml\n")
    else:
        print(f"  API token        →  configured\n")
    app.run(debug=os.environ.get("DEBUG", "1") == "1", host=host, port=port)
