#!/bin/bash

echo "Downloading Paper $MINECRAFT_VERSION-$PAPER_BUILD"
curl "https://api.papermc.io/v2/projects/paper/versions/${MINECRAFT_VERSION}/builds/${PAPER_BUILD}/downloads/paper-${MINECRAFT_VERSION}-${PAPER_BUILD}.jar" -o ./paper.jar

echo "Downloading chunky $CHUNKY_VERSION from hangar"
curl "https://hangarcdn.papermc.io/plugins/pop4959/Chunky/versions/$CHUNKY_VERSION/PAPER/Chunky-$CHUNKY_VERSION.jar" -o ./plugins/chunky.jar
curl "https://cdn.modrinth.com/data/FIlZB9L0/versions/lBGwt5NT/Terra-bukkit-6.4.3-BETA%2Bab60f14ff-shaded.jar" -o ./plugins/terra.jar
curl "https://raw.githubusercontent.com/ukdaaan/app-configs/main/minecraft/pregen/paper-global.yml" -o ./config/paper-global.yml
echo "level-seed=$SEED" > server.properties

java "$JAVA_ARGS" -jar ./paper.jar
