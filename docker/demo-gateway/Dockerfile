FROM python:3.14.6-alpine3.23@sha256:b165067c5afc37fa5608a3c05609cc3d51aafd808a30fbfd822ee594fef55ad4

ARG DEMO_INFRA_REVISION
LABEL org.opencontainers.image.revision="$DEMO_INFRA_REVISION"

WORKDIR /app
COPY docker/demo-gateway/gateway.py /app/gateway.py
COPY docker/demo-gateway/verify_owner_login.py /app/verify_owner_login.py
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["python3", "/app/gateway.py"]
