#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
import java.net.URL
import groovy.xml.*

def call(){

    node {

        stage('Build Java') {

            echo "[Info] - Building Java Code."

            sleep Math.random() * 20
        }

        stage('Unit Tests') {

            echo "[Info] - Run JUnit Tests."

            sleep Math.random() * 20

        }

        stage('Sonar') {           

            echo "[Info] - Scan Java code."

            sleep Math.random() * 20

        }
    }
}
