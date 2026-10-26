#!/usr/bin/env ruby
# Add the screenshot UI test target only to the checkout used by CI.
require 'xcodeproj'

root = File.expand_path('..', __dir__)
project_path = ARGV.fetch(0, File.join(root, 'iosApp/iosApp.xcodeproj'))
project = Xcodeproj::Project.open(project_path)
app = project.targets.find { |target| target.name == 'iosApp' }
abort 'MineMic app target not found' unless app
abort 'Screenshot target already exists' if project.targets.any? { |target| target.name == 'MineMicScreenshots' }

tests = project.new_target(:ui_test_bundle, 'MineMicScreenshots', :ios, '18.2')
tests.add_dependency(app)
group = project.main_group.new_group('MineMicScreenshots', '../tests/ios-screenshots')
tests.source_build_phase.add_file_reference(group.new_file('MineMicScreenshots.swift'))
tests.build_configurations.each do |configuration|
  configuration.build_settings.merge!({
    'PRODUCT_NAME' => 'MineMicScreenshots',
    'PRODUCT_MODULE_NAME' => 'MineMicScreenshots',
    'PRODUCT_BUNDLE_IDENTIFIER' => 'com.aiaixyz.minemic.screenshots',
    'SWIFT_VERSION' => '5.0',
    'GENERATE_INFOPLIST_FILE' => 'YES',
    'CODE_SIGNING_ALLOWED' => 'NO',
    'TEST_TARGET_NAME' => app.name,
    'TARGETED_DEVICE_FAMILY' => '1,2',
    'DEBUG_INFORMATION_FORMAT' => 'dwarf',
    'SWIFT_EMIT_LOC_STRINGS' => 'NO'
  })
end
project.root_object.attributes['TargetAttributes'] ||= {}
project.root_object.attributes['TargetAttributes'][tests.uuid] = { 'TestTargetID' => app.uuid }
project.save

scheme = Xcodeproj::XCScheme.new
scheme.add_build_target(app)
scheme.add_build_target(tests)
scheme.add_test_target(tests)
scheme.test_action.build_configuration = 'Debug'
scheme.save_as(project_path, 'MineMicScreenshots', true)
puts 'Prepared unsigned UI screenshot target; production project settings are not committed by this script.'
