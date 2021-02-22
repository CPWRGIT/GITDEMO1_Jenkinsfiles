#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
import java.net.URL
import groovy.xml.*

def call(){

    node {

        stage('Build Java') {

            echo "[Info] - Loading code to mainframe level " + ispwTargetLevel + "."
        }

        checkForBuildParams(synchConfig.ispw.automaticBuildFile)

        stage('Unit Tests') {

            echo "[Info] - Building code at mainframe level " + ispwTargetLevel + "."

        }

        stage('Sonar') {           

            echo "Unit Tests"

        }
    }
}
