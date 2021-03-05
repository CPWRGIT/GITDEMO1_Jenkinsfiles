#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
import java.net.URL
import groovy.xml.*

def call(){

    def javaRootFolder = './InsuranceSpringServer'

    stage('Unit Tests') {

        echo "[Info] - Running Unit Tests."

        sleep 10

        // dir(javaRootFolder) {

        //     def stdOut = bat(
        //         script:         'gradlew test',
        //         returnStdout:   true
        //     )

        //     echo stdOut

        // }
    }

    stage('Build') {

        echo "[Info] - Building Java Code."

        sleep 15

        // dir(javaRootFolder) {

        //     def stdOut = bat(
        //         script:         'gradlew build',
        //         returnStdout:   true
        //     )

        //     echo stdOut

        // }
    }
}
