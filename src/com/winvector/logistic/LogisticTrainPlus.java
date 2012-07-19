package com.winvector.logistic;

import java.io.File;
import java.util.Date;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.winvector.opt.def.ExampleRow;
import com.winvector.opt.def.LinearContribution;
import com.winvector.opt.def.VEval;
import com.winvector.opt.def.VectorFn;
import com.winvector.opt.def.VectorOptimizer;
import com.winvector.opt.impl.ConjugateGradientOptimizer;
import com.winvector.opt.impl.DataFn;
import com.winvector.opt.impl.ExampleRowIterable;
import com.winvector.opt.impl.HelperFns;
import com.winvector.opt.impl.Newton;
import com.winvector.opt.impl.NormPenalty;
import com.winvector.util.BurstMap;
import com.winvector.variables.BTable;
import com.winvector.variables.PrimaVariableInfo;
import com.winvector.variables.VariableEncodings;

public final class LogisticTrainPlus extends LogisticTrain {
	public int maxExplicitLevels = 100;
	public double newtonRegularization = 1.0e-3;
	public double polishRegularization = 1.0e-5;
	public boolean gradientPolish = false;
	public boolean useIntercept = true;

	@Override
	public Model train(final Iterable<BurstMap> trainSource, final Formula f, final String weightKey) {
		final Log log = LogFactory.getLog(this.getClass());
		log.info("start LogisticTrainPlus");
		log.info("cwd: " + new File(".").getAbsolutePath());
		final PrimaVariableInfo def = LogisticTrain.buildVariableDefs(f,trainSource);
		System.out.println(def.formatState());
		final Set<String> varsToEncode = new HashSet<String>();
		{
			final StringBuilder encList = new StringBuilder();
			for(final String variable: def.catLevels.keySet()) {
				final int support = def.catLevels.get(variable).keySet().size();
				if(support>maxExplicitLevels) {
					varsToEncode.add(variable);
					if(encList.length()>0) {
						encList.append(" ");
					}
					encList.append(variable);
				}
			}
			log.info("re-encoding levels for " + varsToEncode.size() + " varaibles: " + encList);
		}
		final VariableEncodings standardEncodings = new VariableEncodings(def,useIntercept,weightKey);
		final double[] newtonX;
		{
			double[] opt = null;
			double optfx = Double.NEGATIVE_INFINITY;
			final Random rand = new Random(23626236L);
			BTable vectorEncodings = BTable.buildStatBasedEncodings(varsToEncode,trainSource,standardEncodings,null,rand);
			for(int pass=0;pass<5;++pass) {
				log.info("Newton pass: " + pass);
				if(opt!=null) {
					vectorEncodings = BTable.buildStatBasedEncodings(varsToEncode,trainSource,vectorEncodings.newAdapter,opt,rand);
					opt = vectorEncodings.warmStart;
				}
				System.out.println("std dim: " + standardEncodings.dim() + ", adapter dim: " + vectorEncodings.newAdapter.dim());
				final Iterable<ExampleRow> asTrain = new ExampleRowIterable(vectorEncodings.newAdapter,vectorEncodings.sample);
				final LinearContribution<ExampleRow> sigmoidLoss = new SigmoidLossMultinomial(vectorEncodings.newAdapter.dim(),vectorEncodings.newAdapter.noutcomes());
				final VectorFn sl = NormPenalty.addPenalty(new DataFn<ExampleRow,ExampleRow>(sigmoidLoss,asTrain),newtonRegularization);
				final VectorOptimizer nwt = new Newton();
				final VEval newOpt = nwt.maximize(sl,vectorEncodings.warmStart,Integer.MAX_VALUE);
				if((null!=opt)&&(optfx+1.0e-3>=newOpt.fx)) {
					break;
				}
				opt = newOpt.x;
				optfx = newOpt.fx;
				log.info("done Newton training\t" + new Date());
				//final double trainAccuracy = HelperFns.accuracy(sigmoidLoss,asTrain,opt);
				//log.info("train accuracy:" + trainAccuracy);
			}
			// decode x to standard basis
			newtonX = vectorEncodings.translateTo(standardEncodings,opt);
		}
		final Model model = new Model();
		model.config = standardEncodings;
		model.origFormula = f;
		// gradient polish
		if(gradientPolish) {
			//final VectorOptimizer polisher = new GradientDescent();
			final VectorOptimizer polisher = new ConjugateGradientOptimizer();
			final Iterable<ExampleRow> asTrain = new ExampleRowIterable(standardEncodings,trainSource);
			final LinearContribution<ExampleRow> sigmoidLoss = new SigmoidLossMultinomial(standardEncodings.dim(),standardEncodings.noutcomes());
			final VectorFn sl = NormPenalty.addPenalty(new DataFn<ExampleRow,ExampleRow>(sigmoidLoss,asTrain),polishRegularization);
			final VEval opt = polisher.maximize(sl,newtonX,5);
			log.info("done gradient polish training\t" + new Date());
			//log.info("soln vector: " + LinUtil.toString(opt.x));
			//log.info("soln details:\n" + standardEncodings.formatSoln(opt.x));
			model.coefs = opt.x;
			//final double trainAccuracy = HelperFns.accuracy(sigmoidLoss,asTrain,opt.x);
			//log.info("train accuracy: " + trainAccuracy);
			///log.info("done LogisticTrain");
		} else {
			model.coefs = newtonX;
		}
		final LinearContribution<ExampleRow> sigmoidLoss = new SigmoidLossMultinomial(standardEncodings.dim(),standardEncodings.noutcomes());
		final Iterable<ExampleRow> asTrain = new ExampleRowIterable(standardEncodings,trainSource);
		final double trainAccuracy = HelperFns.accuracy(sigmoidLoss,asTrain,model.coefs);
		log.info("train accuracy:" + trainAccuracy);
		return model;
	}
}