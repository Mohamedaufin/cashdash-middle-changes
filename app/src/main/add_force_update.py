import os

path = r's:\AndroidStudioProjects\AndroidStudioProjects\cashdash\app\src\main\AndroidManifest.xml'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

if 'ForceUpdateActivity' not in content:
    content = content.replace('        <activity android:configChanges="uiMode"\n            android:name=".AdminActivity"', '''        <activity android:configChanges="uiMode"
            android:name=".ForceUpdateActivity"
            android:exported="false"
            android:screenOrientation="portrait" />

        <activity android:configChanges="uiMode"
            android:name=".AdminActivity"''')
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)
