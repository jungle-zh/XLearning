
nohup /home/hadoop/xlearning-1.2/bin/xl-submit \
   --app-type "tensorflow" \
   --app-name "tf-demo" \
   --input model-data1/test/data#data \
   --output model-data1/tmp/tensorflow_model#model \
   --files demo.py,dataDeal.py \
   --launch-cmd "./anaconda3/anaconda3/bin/python model_lr.py --data_path=./data --save_path=./model --log_dir=./eventLog --training_epochs=2"  \
   --user-path  ./anaconda3/anaconda3/bin \
   --cacheArchive  /tmp/anaconda3.zip#anaconda3 \
   --worker-memory 2G \
   --worker-num 2 \
   --worker-cores 1 \
   --ps-memory 2G \
   --ps-num 1 \
   --ps-cores 2 \
   --use-s3 yes \
   --queue default \
    &

   #--launch-cmd "python demo.py --data_path=./data --save_path=./model --log_dir=./eventLog --training_epochs=10" \
