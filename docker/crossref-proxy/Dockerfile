FROM python:3.14.6-alpine3.23@sha256:b165067c5afc37fa5608a3c05609cc3d51aafd808a30fbfd822ee594fef55ad4

WORKDIR /app
COPY docker/crossref-proxy/proxy.py /app/proxy.py
COPY docker/crossref-proxy/stub.py /app/stub.py
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["python3", "/app/proxy.py"]
