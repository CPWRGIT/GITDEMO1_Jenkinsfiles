#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
import java.net.URL
import groovy.xml.*

String synchConfigFile         
String branchMappingString     
String ispwTargetLevel
String ispwImpactScanJcl
String ccDdioOverrides     
String sonarCobolFolder        
String sonarCopybookFolder     
String sonarResultsFile   
String sonarResultsFileVt
String sonarResultsFileNvtBatch
String sonarResultsFileNvtCics
String sonarResultsFileList     
String sonarCodeCoverageFile   
String jUnitResultsFile
String executionType
String skipReason

String tttVtExecutionLoad

Boolean skipTests

def pipelineParms
def ispwConfig
def synchConfig

def CC_TEST_ID_MAX_LEN
def CC_SYSTEM_ID_MAX_LEN

def EXECUTION_TYPE_NO_MF_CODE
def EXECUTION_TYPE_VT_ONLY
def EXECUTION_TYPE_NVT_ONLY
def EXECUTION_TYPE_BOTH

def call(Map execParms){

    //**********************************************************************
    // Start of Script 
    //**********************************************************************
    node {

        stage ('Checkout and initialize') {

            dir('./') {
                deleteDir()
            }

            //checkout scm
            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: '*/feature/FT1/demo_feature']], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[url: 'https://github.com/CPWRGIT/HDDRXM0.git']]]
            stash name: 'workspace'
        }

        parallel(

            mfCode: {
                mfCodePipeline(execParms)
            },
            javaCode: {
                javaCodePipeline()
            },
            failFast: true

        )
    }
}