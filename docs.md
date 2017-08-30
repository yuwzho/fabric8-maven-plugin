# Use fabric8 maven plugin to deploy to kubernetes cluster

## Introduction
This document describe how to apply your Java project to [kubernetes](https://kubernetes.io/) cluster with [fabric8-maven-plugin](https://maven.fabric8.io/).

## Prequisite
- JDK 1.8 and above
- [Maven](http://maven.apache.org/) 3.0 and above

## Create kubernetes cluster for Linux containers on Azure
refer [this page](https://docs.microsoft.com/en-us/azure/container-service/kubernetes/container-service-kubernetes-walkthrough)'s sections before `Run the application` to create your kubernetes cluster, and download the kubernetes credentials to local.

## Add plugin to your Java project
### Configure the `pom.xml`
Include this configuration in your project `pom.xml`: 

```xml
<plugin>
  <groupId>io.fabric8</groupId>
  <artifactId>fabric8-maven-plugin</artifactId>
  <version>latest</version>
  ...
</plugin>
```

### Add kubernetes resource yaml file

Put each kubernetes resource yaml file under `src/fabric8` folder. Each file should contain and only contain a kubernetes resource in yaml format. The maven plugin will merge these resources into a list and apply to cluster. See [example](#example) for more details.

### Apply kubernetes resource yaml file to kubernetes cluster
Goal | Description
-- | --
fabric8:resource | merge all kubernetes resource yaml files under `src/fabric8` folder to a yaml file contains kubernetes resource list, which can be applied to kubernetes cluster directly or export to a helm chart.
fabric8:apply | apply the kubernetes resource list created by goal `fabric8:resource` to cluster

1. Build the kubernetes resource list file by using
    ```bash
    mvn fabric8:resource
    ```
    
2. Apply the kubernetes resource list file to cluster by using
    ```bash
    mvn fabric8:apply
    ```
    
### example

Use sample [secret-config](samples/secret-config) as example, to deploy a string boot Hello World project to kubernetes.

**create kubernetes yaml resource fragments** 
1. create a `deployment.yml` to contain a Deployment resource.
    ```yaml
    apiVersion: extensions/v1beta1
    kind: Deployment
    metadata:
      ...
    spec:
      ...
      template:
        metadata:
          ...
        spec:
          containers:
          - image: ${imageName}
            name: spring
            ports:
            - containerPort: 8080
          imagePullPolicy: Always
          imagePullSecrets:
            - name: ${dockerKeyName}
    ```
1. create a `docker.yml` to store private docker registry information
    ```yaml
    apiVersion: v1
    kind: Secret
    metadata:
      ...
      name: ${dockerKeyName}
      annotations:
        maven.fabric8.io/dockerServerId: ${docker.registry}
    type: kubernetes.io/dockercfg
    ```



**Properties**

Property | Required | Description
-- | -- | --
${imageName} | true | Specifies the Docker image name: <br/> Specifies the Docker image name. Valid image name formats are listed as below.<br>- Docker Hub image: `[hub-user/]repo-name[:tag]`; `tag` is optional, default value is **latest**.<br>- Private registry image: `hostname/repo-name[:tag]`; `tag` is optional, default value is **latest**.
${dockerKeyName} | false | The docker registry authentication name store in kubernetes cluster. If the docker image is from a private docker registry, this one is required.
${docker.registry} | false | Docker registry hostname. Default is **docker.io**. If it is a private docker registry, it should be the `Id` from your Maven `settings.xml`'s `server` section with username and password.

**Bind goals to lifecycle**
```xml
<plugin>
  <groupId>io.fabric8</groupId>
  <artifactId>fabric8-maven-plugin</artifactId>
  <version>latest</version>
  ...
  <configuration>
    <ignoreServices>true</ignoreServices>
  </configuration>
  <executions>
    <execution>
       <id>fabric8</id>
       <goals>
         <goal>resource</goal>
         <goal>apply</goal>
       </goals>
    </execution>
  </executions>
</plugins>
```
**Compile**

Use `mvn compile`, this command will invoke goal `fabric8:resource` to create kubernetes resource list file.

**Deploy**

Use `mvn install`, this command will invoke goal `fabric8:apply` to apply kubernetes resource list file to cluster.
