"""Loopback-only test TLS termination. No plaintext persistence or payload logging."""
import http.client
import http.server
import ssl
import sys


class Relay(http.server.BaseHTTPRequestHandler):
    def forward(self):
        size = int(self.headers.get("Content-Length", "0"))
        if size > 1_200_000:
            self.send_error(413)
            return
        body = self.rfile.read(size) if size else None
        connection = http.client.HTTPConnection("127.0.0.1", 18444, timeout=30)
        try:
            connection.request(self.command, self.path, body, {k: v for k, v in self.headers.items() if k.lower() not in ("host", "connection")})
            response = connection.getresponse()
            data = response.read()
            self.send_response(response.status)
            self.send_header("Content-Type", response.getheader("Content-Type", "application/json"))
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        finally:
            connection.close()

    do_GET = forward
    do_POST = forward

    def log_message(self, *args):
        pass


server = http.server.ThreadingHTTPServer(("127.0.0.1", 18445), Relay)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(sys.argv[1], sys.argv[2])
server.socket = context.wrap_socket(server.socket, server_side=True)
server.serve_forever()
