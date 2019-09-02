#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
import groovy.json.JsonSlurper
import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import jenkins.plugins.http_request.*
import java.net.URL
import com.compuware.devops.config.*
import com.compuware.devops.jclskeleton.*
import com.compuware.devops.util.*

PipelineConfig  pConfig
GitHelper       gitHelper
IspwHelper      ispwHelper
TttHelper       tttHelper
SonarHelper     sonarHelper 

String          mailMessageExtension

def call(Map pipelineParams)
{
    node
    {        
        echo "Source Level: ${pipelineParams.ISPW_Src_Level}"
        pipelineParams.ISPW_Src_Level = pipelineParams.ISPW_Src_Level.replace('DEV', 'QA')
        echo "Source Level: ${pipelineParams.ISPW_Src_Level}"

        def generatePipeline = Mainframe_Generate_Pipeline(pipelineParams)

        stage("Promote")
        {
            echo "Starting Promote"
        }
    }
}