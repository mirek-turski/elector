#!/bin/bash

mvn clean package -DskipTests
kubectl delete -n default deployment elecetor-demo
kubectl delete -n default service elector-demo
sleep 3
docker rmi elector-demo:latest
docker build -t elector-demo .
kubectl apply -f elector-demo-deployment.yml
