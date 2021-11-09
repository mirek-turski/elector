#!/bin/bash

mvn clean package -DskipTests
kubectl delete -n default deployment self-rule-demo
kubectl delete -n default service self-rule-demo
sleep 3
docker rmi self-rule-demo:latest
docker build -t self-rule-demo .
kubectl apply -f self-rule-demo-deployment.yml
