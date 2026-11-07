"""Creates (or refreshes) the Gradle project of every loader/version port under ports/<loader>/<version>/.

Run from anywhere: python ports/make_ports.py
Each port only holds build settings; the code comes from src/ (shared), versions/ and ports/<loader>/glue/
(see ports/sources.gradle).
"""
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MOD_VERSION = '1.0.0'

# label -> settings. "label" is the folder name and the Minecraft version printed in the jar name.
FABRIC = {
    '26.1': dict(minecraft_version='26.1', minecraft_range='>=26.1 <26.2', fabric_api_version='0.145.1+26.1', versions='26.1,26.x'),
    '26.2': dict(minecraft_version='26.2', minecraft_range='~26.2', fabric_api_version='0.161.0+26.2', versions='26.2,26.x'),
    '26.3': dict(minecraft_version='26.3', minecraft_range='~26.3', fabric_api_version='0.161.0+26.3', versions='26.3,26.x'),
}
NEOFORGE = {
    '1.21.11': dict(minecraft_version='1.21.11', minecraft_range='[1.21.11]', neo_version='21.11.48', java='21', versions=''),
    '26.1': dict(jar_label='26.1.2', minecraft_version='26.1.2', minecraft_range='[26.1.2]', neo_version='26.1.2.126', java='25', versions='26.1,26.x'),
    '26.2': dict(minecraft_version='26.2', minecraft_range='[26.2]', neo_version='26.2.0.93', java='25', versions='26.2,26.x'),
    '26.3': dict(minecraft_version='26.3', minecraft_range='[26.3]', neo_version='26.3.0.101', java='25', versions='26.3,26.x'),
}
FORGE = {
    '1.21.11': dict(minecraft_version='1.21.11', forge_version='1.21.11-61.2.5', loader_range='[61,)', minecraft_range='[1.21.11,1.21.12)', java='21', fg='[7.0.3,8)', validator='7.0.5', versions='', mappings=True),
    '26.1': dict(jar_label='26.1.2', minecraft_version='26.1.2', forge_version='26.1.2-64.1.6', loader_range='[64,)', minecraft_range='[26.1.2,26.2)', java='25', fg='[7.0.17,8)', validator='7.0.6', versions='26.1,26.x', mappings=False),
    '26.2': dict(minecraft_version='26.2', forge_version='26.2-65.1.6', loader_range='[65,)', minecraft_range='[26.2,26.3)', java='25', fg='[7.0.17,8)', validator='7.0.6', versions='26.2,26.x', mappings=False),
    '26.3': dict(minecraft_version='26.3', forge_version='26.3-66.1.5', loader_range='[66,)', minecraft_range='[26.3,26.4)', java='25', fg='[7.0.17,8)', validator='7.0.6', versions='26.3,26.x', mappings=False),
}


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)


def wrapper(port_dir, gradle_version):
    src = os.path.join(ROOT, 'gradle', 'wrapper', 'gradle-wrapper.jar')
    dst_dir = os.path.join(port_dir, 'gradle', 'wrapper')
    os.makedirs(dst_dir, exist_ok=True)
    shutil.copyfile(src, os.path.join(dst_dir, 'gradle-wrapper.jar'))
    write(os.path.join(dst_dir, 'gradle-wrapper.properties'),
          'distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n'
          f'distributionUrl=https\\://services.gradle.org/distributions/gradle-{gradle_version}-bin.zip\n'
          'networkTimeout=10000\nvalidateDistributionUrl=true\nzipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n')
    for name in ('gradlew', 'gradlew.bat'):
        shutil.copyfile(os.path.join(ROOT, name), os.path.join(port_dir, name))


def props(d):
    return ''.join(f'{k}={v}\n' for k, v in d.items())


def fabric(label, s):
    d = os.path.join(HERE, 'fabric', label)
    write(os.path.join(d, 'settings.gradle'),
          "pluginManagement {\n\trepositories {\n\t\tmaven { name = 'Fabric'; url = 'https://maven.fabricmc.net/' }\n"
          "\t\tmavenCentral()\n\t\tgradlePluginPortal()\n\t}\n}\n\nrootProject.name = 'chesscraft-fabric-" + label + "'\n")
    write(os.path.join(d, 'build.gradle'),
          "plugins {\n\tid 'net.fabricmc.fabric-loom' version \"${loom_version}\"\n}\n\napply from: '../fabric-26.gradle'\n")
    write(os.path.join(d, 'gradle.properties'), props({
        'org.gradle.jvmargs': '-Xmx3G',
        'chess_loader': 'fabric', 'chess_label': label, 'chess_versions': s['versions'],
        'mod_version': MOD_VERSION, 'minecraft_version': s['minecraft_version'], 'minecraft_range': s['minecraft_range'],
        'loader_version': '0.19.5', 'loom_version': '1.18-SNAPSHOT', 'fabric_api_version': s['fabric_api_version'],
    }))
    wrapper(d, '9.7.1')


def neoforge(label, s):
    d = os.path.join(HERE, 'neoforge', label)
    write(os.path.join(d, 'settings.gradle'),
          "pluginManagement {\n\trepositories {\n\t\tgradlePluginPortal()\n\t\tmaven { url = 'https://maven.neoforged.net/releases' }\n\t}\n}\n\n"
          "plugins {\n\tid 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'\n}\n\n"
          "rootProject.name = 'chesscraft-neoforge-" + label + "'\n")
    write(os.path.join(d, 'build.gradle'),
          "plugins {\n\tid 'java-library'\n\tid 'net.neoforged.moddev' version '2.0.148'\n}\n\napply from: '../neoforge.gradle'\n")
    write(os.path.join(d, 'gradle.properties'), props({
        'org.gradle.jvmargs': '-Xmx3G',
        'chess_loader': 'neoforge', 'chess_label': s.get('jar_label', label), 'chess_versions': s['versions'],
        'mod_version': MOD_VERSION, 'minecraft_version': s['minecraft_version'], 'minecraft_range': s['minecraft_range'],
        'neo_version': s['neo_version'], 'java_version': s['java'],
    }))
    wrapper(d, '9.7.1')


def forge(label, s):
    d = os.path.join(HERE, 'forge', label)
    write(os.path.join(d, 'settings.gradle'),
          "plugins {\n\tid 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'\n}\n\n"
          "rootProject.name = 'chesscraft-forge-" + label + "'\n")
    write(os.path.join(d, 'build.gradle'),
          "plugins {\n\tid 'java'\n\tid 'net.minecraftforge.gradle' version '" + s['fg'] + "'\n}\n\napply from: '../forge.gradle'\n")
    write(os.path.join(d, 'gradle.properties'), props({
        'org.gradle.jvmargs': '-Xmx3G',
        'net.minecraftforge.gradle.merge-source-sets': 'true',
        'chess_loader': 'forge', 'chess_label': s.get('jar_label', label), 'chess_versions': s['versions'],
        'mod_version': MOD_VERSION, 'minecraft_version': s['minecraft_version'], 'minecraft_range': s['minecraft_range'],
        'forge_version': s['forge_version'], 'forge_loader_range': s['loader_range'], 'java_version': s['java'],
        'eventbus_validator': s['validator'], 'official_mappings': str(s['mappings']).lower(),
    }))
    wrapper(d, '9.7.1')


if __name__ == '__main__':
    for k, v in FABRIC.items():
        fabric(k, v)
    for k, v in NEOFORGE.items():
        neoforge(k, v)
    for k, v in FORGE.items():
        forge(k, v)
    print('ports written')
