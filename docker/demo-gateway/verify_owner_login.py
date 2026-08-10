#!/usr/bin/env python3
import http.cookiejar
import json
import sys
from urllib.parse import urlencode, urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, HTTPCookieProcessor, Request, build_opener


class SameOriginRedirect(HTTPRedirectHandler):
    def __init__(self, origin):
        self.origin = urlsplit(origin)[:2]

    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        target = urlsplit(urljoin(request.full_url, new_url))
        if target[:2] != self.origin:
            raise RuntimeError("cross-origin redirect rejected")
        return super().redirect_request(request, file_pointer, code, message, headers, new_url)


def verify_owner(origin, password):
    origin = origin.rstrip("/")
    cookies = http.cookiejar.CookieJar()
    opener = build_opener(HTTPCookieProcessor(cookies), SameOriginRedirect(origin))
    with opener.open(origin + "/login", timeout=5):
        pass
    body = urlencode({
        "j_username": "heojungseok",
        "j_password": password,
        "from": "/manage",
        "Submit": "Sign in",
    }).encode()
    request = Request(origin + "/j_spring_security_check", data=body, method="POST")
    with opener.open(request, timeout=5):
        pass
    if any(cookie.secure for cookie in cookies):
        raise RuntimeError("secure cookie returned over HTTP owner endpoint")
    with opener.open(origin + "/whoAmI/api/json", timeout=5) as response:
        identity = json.load(response)
    if not identity.get("authenticated") or identity.get("name") != "heojungseok":
        raise RuntimeError("owner login rejected")
    with opener.open(origin + "/manage", timeout=5):
        pass
    with opener.open(origin + "/crumbIssuer/api/json", timeout=5) as response:
        crumb = json.load(response)
    crumb_field, crumb_value = crumb["crumbRequestField"], crumb["crumb"]
    logout = Request(
        origin + "/logout", data=urlencode({crumb_field: crumb_value}).encode(), method="POST",
        headers={crumb_field: crumb_value, "Content-Type": "application/x-www-form-urlencoded"},
    )
    with opener.open(logout, timeout=5):
        pass


if __name__ == "__main__":
    try:
        verify_owner(sys.argv[1], sys.stdin.read().rstrip("\r\n"))
    except Exception as error:
        raise SystemExit(str(error)) from error
    print("owner session verified")
