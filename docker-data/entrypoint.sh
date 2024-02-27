#!/bin/bash

echo "Downloading Paper $MINECRAFT_VERSION-$PAPER_BUILD"
curl "https://api.papermc.io/v2/projects/paper/versions/${MINECRAFT_VERSION}/builds/${PAPER_BUILD}/downloads/paper-${MINECRAFT_VERSION}-${PAPER_BUILD}.jar" -o ./paper.jar

echo "Downloading chunky $CHUNKY_VERSION from hangar"
curl "https://hangarcdn.papermc.io/plugins/pop4959/Chunky/versions/$CHUNKY_VERSION/PAPER/Chunky-$CHUNKY_VERSION.jar" -o ./plugins/chunky.jar
echo "level-seed=$SEED" > server.properties

java "$JAVA_ARGS" -jar ./paper.jar
