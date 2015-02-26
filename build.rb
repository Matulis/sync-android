#!/usr/bin/ruby 
#
#  -couch <type> couchdb1.6, couchdb2.0, cloudantSAAS, cloudantlocal - default is couchdb1.6

#  -plaform <platfrom> java | android default is Java
#  -D* gets passed into build.
#  
#

params = { :d_options => Array.new }
arg_is_value = false 
prev_arg = nil


ARGV.each do |arg|

	puts arg

 if arg.start_with?("-D")
    params[:d_options].push(arg)
 	next
 end

 #prcoess arguments into a hash
 unless arg_is_value 
 	params[arg[1,arg.length] ] = nil
 	$prev_arg = arg[1,arg.length] 
 	arg_is_value = true
 else
 	params[$prev_arg] = arg
 	arg_is_value = false 
 end

end

#apply defaults
params["platform"] = "java" unless params["platform"] 
params["couch"] = "couchdb1.6" unless params["couch"]


#launch docker
puts "Starting docker container #{$couch}"
puts "Commandline: docker run -p 5984:5984 -d --name 'couchdb' #{params["couch"]}"

unless system("docker run -p 5984:15984 -d --name 'couchdb' #{params["couch"]}")
	#we need to stop, we failed to run the docker container, just in case we will delete
	system("docker rm couchdb")

end

puts "Performing build"
#make gradlew executable
system("chmod a+x ./gradlew ") 

#handle the differences in the platform
if params["platform"] == "java"
	system("./gradlew #{params[:d_options].join(" ")} clean check integrationTest")
elsif params["platform"] == "android"
	system("./gradlew -b AndroidTest/build.gradle #{params[:d_options].join(" ")} clean installStandardTestDebug waitForTestAppToFinish")
end

#get the build exit code, will exit with this after tearing down the docker container
exitcode = $?

puts "Tearing down docker container"

system("docker stop couchdb")

system("docker rm couchdb")

exit exitcode.to_i
