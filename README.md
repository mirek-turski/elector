# Elector
Leadership election for Spring services based on service discovery provided by [Spring Cloud Kubernetes](https://spring.io/projects/spring-cloud-kubernetes).

  The project allows self-sustained instance management for services deployed to Kubernetes.
  The algorithm works by applying to new instances ordered numbers from a pool of defined size.
  For the pool size equal to one, it behaves as simple leader election with one master instance and the rest marked as minions (spare instances).
  Bigger pool sizes can be used for services that are dependent on some external factors like limited pool of connections any given service can use.
  Any instance with an order number assigned becomes active and based on the number may be able to determine the portion of configuration it will use.

```
eval $(minikube -p minikube docker-env)
kubectl apply -f ./update-default-service-account.yml -n default
/bin/sh ./kubernetes-deploy.sh
```