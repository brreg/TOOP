#!/bin/sh
oc login https://api.ocp-prd.regsys.brreg.no:6443 &&
oc project toop &&
mvn clean install &&
docker push quay.apps.ocp-svc.base.brreg.no/toop/toop-smp:latest &&
docker push quay.apps.ocp-svc.base.brreg.no/toop/toop-connector:latest &&
oc import-image toop-smp &&
oc import-image toop-connector &&
df -h|grep "/var$"
