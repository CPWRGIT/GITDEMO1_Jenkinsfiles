#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
import java.net.URL
import groovy.xml.*

def call(){

    node {

        stage('Build Java') {

            echo "[Info] - Building Java Code."

            sleep 20
        }

        checkForBuildParams(synchConfig.ispw.automaticBuildFile)

        stage('Unit Tests') {

            echo "[Info] - Run JUnit Tests."

            sleep 20

        }

        stage('Sonar') {           

            echo "[Info] - Scan Java code."

            sleep 20

        }
    }
}
