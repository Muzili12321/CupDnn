package cupcnn.layer;



import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Vector;

import cupcnn.Network;
import cupcnn.active.ReluActivationFunc;
import cupcnn.active.SigmodActivationFunc;
import cupcnn.active.TanhActivationFunc;
import cupcnn.data.Blob;
import cupcnn.data.BlobParams;
import cupcnn.util.MathFunctions;
import cupcnn.util.Task;
import cupcnn.util.ThreadPoolManager;

/*
 * 深度可分离卷积
 * 深度可分离卷积要求输出通道是输入通道的整数倍
 * 比如输入通道是6，则输出通道只能是6，12，18，24......
 */
public class DeepWiseConv2dLayer extends Layer{
	public static final String TYPE = "DeepWiseConv2dLayer";
	private Blob kernel;
	private Blob bias;
	private Blob kernelGradient;
	private Blob biasGradient;
	private Blob z;
	private int width;
	private int height;
	private int inChannel;
	private int outChannel;
	private int kernelSize;
	private int stride;
	
	public DeepWiseConv2dLayer(Network network){
		super(network);
	}

	public DeepWiseConv2dLayer(Network network, int width,int height,int inChannel,int outChannel,int kernelSize,int stride) {
		// TODO Auto-generated constructor stub
		super(network);
		this.width = width;
		this.height = height;
		this.inChannel = inChannel;
		this.outChannel = outChannel;
		this.kernelSize = kernelSize;
		this.stride = stride;
	}

	@Override
	public String getType() {
		// TODO Auto-generated method stub
		return TYPE;
	}

	@Override
	public void prepare() {
		// TODO Auto-generated method stub
		Blob output = mNetwork.getDatas().get(id);
		//layerParams.getHeight()表示该层需要提取的特征数量
		if(kernel ==null && bias == null){
			kernel = new Blob(mNetwork.getBatch(),outChannel,kernelSize,kernelSize);
			bias = new Blob(mNetwork.getBatch(),outChannel,1,1);
			//init params
			MathFunctions.gaussianInitData(kernel.getData());
			MathFunctions.constantInitData(bias.getData(), 0.1);
		}
		assert kernel != null && bias != null :"ConvolutionLayer prepare----- kernel is null or bias is null error";
		z = new Blob(output.getNumbers(),output.getChannels(),output.getHeight(),output.getWidth());
		kernelGradient = new Blob(kernel.getNumbers(),kernel.getChannels(),kernel.getHeight(),kernel.getWidth());
		biasGradient = new Blob(bias.getNumbers(),bias.getChannels(),bias.getHeight(),bias.getWidth());

	}

	@Override
	public void forward() {
		// TODO Auto-generated method stub
		Blob input = mNetwork.getDatas().get(id-1);
		Blob output = mNetwork.getDatas().get(id);
		double [] outputData = output.getData();
		double [] zData = z.getData();
		//卷积后的结果存贮在z中
		z.fillValue(0);
		MathFunctions.deepWiseConv2dSame(mNetwork,input, kernel, bias, z);
		//激活函数
		if(activationFunc!=null){
			Vector<Task<Object>> workers = new Vector<Task<Object>>();
			for(int n=0;n<output.getNumbers();n++){
				workers.add(new Task<Object>(n) {
					@Override
				    public Object call() throws Exception {
						for(int c=0;c<output.getChannels();c++){
							for(int h=0;h<output.getHeight();h++){
								for(int w=0;w<output.getWidth();w++){
									outputData[output.getIndexByParams(n, c, h, w)] = activationFunc.active(zData[z.getIndexByParams(n, c, h, w)]);
								}
							}
						}
						return null;
					}
				});
			}
			ThreadPoolManager.getInstance(mNetwork).dispatchTask(workers);
		}
	}

	@Override
	public void backward() {
		// TODO Auto-generated method stub
		Blob input = mNetwork.getDatas().get(id-1);
		Blob inputDiff = mNetwork.getDiffs().get(id);
		Blob outputDiff = mNetwork.getDiffs().get(id-1);
		double[] inputDiffData = inputDiff.getData();
		double[] zData = z.getData();
		double[] kernelGradientData = kernelGradient.getData();
		double[] inputData = input.getData();
		double[] biasGradientData = biasGradient.getData();
		
		//先乘激活函数的导数,得到该层的误差
		Vector<Task<Object>> workers = new Vector<Task<Object>>();
		if(activationFunc!=null){
			for(int n=0;n<inputDiff.getNumbers();n++){
				workers.add(new Task<Object>(n) {
					@Override
				    public Object call() throws Exception {
						for(int c=0;c<inputDiff.getChannels();c++){
							for(int h=0;h<inputDiff.getHeight();h++){
								for(int w=0;w<inputDiff.getWidth();w++){
									inputDiffData[inputDiff.getIndexByParams(n, c, h, w)] *= activationFunc.diffActive(zData[z.getIndexByParams(n, c, h, w)]);
								}
							}
						}
						return null;
					}
				});
			}
			ThreadPoolManager.getInstance(mNetwork).dispatchTask(workers);
		}
		
		//然后更新参数
		//计算kernelGradient,这里并不更新kernel,kernel在优化器中更新
		kernelGradient.fillValue(0);
		workers.clear();
		for(int n=0;n<inputDiff.getNumbers();n++){
			workers.add(new Task<Object>(n) {
				@Override
			    public Object call() throws Exception {
					for(int c=0;c<inputDiff.getChannels();c++){
						int inputChannelIndex = c/(inputDiff.getChannels()/input.getChannels());
						for(int h=0;h<inputDiff.getHeight();h++){
							for(int w=0;w<inputDiff.getWidth();w++){
								//先定位到输出的位置
								//然后遍历kernel,通过kernel定位输入的位置
								//然后将输入乘以diff
								int inStartX = w - kernelGradient.getWidth()/2;
								int inStartY = h - kernelGradient.getHeight()/2;
								//和卷积核乘加
					
								for(int kh=0;kh<kernelGradient.getHeight();kh++){
									for(int kw=0;kw<kernelGradient.getWidth();kw++){
										int inY = inStartY + kh;
										int inX = inStartX + kw;
										if (inY >= 0 && inY < input.getHeight() && inX >= 0 && inX < input.getWidth()){
											kernelGradientData[kernelGradient.getIndexByParams(0,  c, kh, kw)] += inputData[input.getIndexByParams(n,inputChannelIndex , inY, inX)]
													*inputDiffData[inputDiff.getIndexByParams(n, c, h, w)];
										}
									}
								}
							}
						}
					}
					return null;
				}
			});
		}
		ThreadPoolManager.getInstance(mNetwork).dispatchTask(workers);
		//平均
		MathFunctions.dataDivConstant(kernelGradientData, inputDiff.getNumbers());
		
		//更新bias
		biasGradient.fillValue(0);
		for(int n=0;n<inputDiff.getNumbers();n++){
			for(int c=0;c<inputDiff.getChannels();c++){
				for(int h=0;h<inputDiff.getHeight();h++){
					for(int w=0;w<inputDiff.getWidth();w++){
						biasGradientData[bias.getIndexByParams(0, c, 0, 0)] += inputDiffData[inputDiff.getIndexByParams(n, c, h, w)];
					}
				}
			}
		}
		//平均
		MathFunctions.dataDivConstant(biasGradientData, inputDiff.getNumbers());
		
		if(id<=1)return;
		//先把kernel旋转180度
		//Blob kernelRoate180 = MathFunctions.rotate180Blob(kernel);
		//然后再做卷积
		outputDiff.fillValue(0);
		MathFunctions.deepWiseConv2dSame(mNetwork,inputDiff, kernel, outputDiff);	
		
		paramsListW.clear();
		paramsListW.add(kernel);
		paramsListB.clear();
		paramsListB.add(bias);
		
		gradientListW.clear();
		gradientListW.add(kernelGradient);
		gradientListB.clear();
		gradientListB.add(biasGradient);
	}

	@Override
	public void saveModel(ObjectOutputStream out) {
		// TODO Auto-generated method stub
		try {
			out.writeUTF(getType());
			out.writeUTF(getType());
			out.writeInt(width);
			out.writeInt(height);
			out.writeInt(inChannel);
			out.writeInt(outChannel);
			out.writeInt(kernelSize);
			out.writeInt(stride);
			out.writeObject(kernel);
			out.writeObject(bias);
			if(activationFunc != null){
				out.writeUTF(activationFunc.getType());
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}

	@Override
	public void loadModel(ObjectInputStream in) {
		// TODO Auto-generated method stub
		try {
			width = in.readInt();
			height = in.readInt();
			inChannel = in.readInt();
			outChannel = in.readInt();
			kernelSize = in.readInt();
			stride = in.readInt();
			kernel = (Blob) in.readObject();
			bias = (Blob) in.readObject();
			String activationType = in.readUTF();
			if(activationType.equals(ReluActivationFunc.TYPE)){
				setActivationFunc(new ReluActivationFunc());
			}else if(activationType.equals(SigmodActivationFunc.TYPE)){
				setActivationFunc(new SigmodActivationFunc());
			}else if(activationType.equals(TanhActivationFunc.TYPE)){
				setActivationFunc(new TanhActivationFunc());
			}
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	public Blob createOutBlob() {
		// TODO Auto-generated method stub
		return new Blob(mNetwork.getBatch(),outChannel,height,width);
	}

	@Override
	public Blob createDiffBlob() {
		// TODO Auto-generated method stub
		return new Blob(mNetwork.getBatch(),outChannel,height,width);
	}
}
