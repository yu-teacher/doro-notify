#!/usr/bin/env bash
# VAPID(RFC 8292) 키 쌍 만들기. source 해서 쓴다: vapid_generate 가 VAPID_PUBLIC·VAPID_PRIVATE 변수에 값을 채운다.
# 값은 **출력하지 않는다**(호출한 쪽이 파일에 바로 쓴다). 형식은 서비스가 읽는 것과 같다:
#   공개 키 = 비압축 P-256 점 65바이트(0x04||X||Y)의 base64url, 개인 키 = 32바이트 스칼라의 base64url(패딩 없음).
# 필요: openssl, python3.

_vapid_b64url() { # 16진수 문자열 -> base64url(패딩 없음)
  python3 -I -c 'import sys,base64,binascii; print(base64.urlsafe_b64encode(binascii.unhexlify(sys.argv[1])).decode().rstrip("="))' "$1"
}

vapid_generate() {
  local pem text priv pub
  command -v openssl >/dev/null 2>&1 || { echo "openssl 이 필요하다" >&2; return 1; }
  command -v python3 >/dev/null 2>&1 || { echo "python3 이 필요하다" >&2; return 1; }
  pem="$(openssl ecparam -name prime256v1 -genkey -noout 2>/dev/null)" || return 1
  text="$(printf '%s\n' "$pem" | openssl ec -text -noout 2>/dev/null)" || return 1
  priv="$(printf '%s\n' "$text" | awk '/^priv:/{f=1;next} /^pub:/{f=0} f' | tr -d ' :\n')"
  pub="$(printf '%s\n' "$text" | awk '/^pub:/{f=1;next} /^ASN1 OID|^NIST CURVE/{f=0} f' | tr -d ' :\n')"
  priv="${priv: -64}"   # 앞에 붙은 00 부호 바이트를 떼고 32바이트만 쓴다
  [ "${#priv}" -eq 64 ] && [ "${#pub}" -eq 130 ] || { echo "키 형식을 읽지 못했다" >&2; return 1; }
  VAPID_PRIVATE="$(_vapid_b64url "$priv")"
  VAPID_PUBLIC="$(_vapid_b64url "$pub")"
}
