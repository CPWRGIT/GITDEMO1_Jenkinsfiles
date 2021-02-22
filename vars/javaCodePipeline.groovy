#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
import java.net.URL
import groovy.xml.*

def call(){

    node {

        stage('Build') {

            echo "[Info] - Building Java Code."

            sleep Math.random() * 20
        }

        stage('Tests') {

            echo "[Info] - Run JUnit Tests."

            sleep Math.random() * 20

        }

        stage('Deploy') {

            echo "[Info] - Run JUnit Tests."

            sleep Math.random() * 20

        }
    }
}
