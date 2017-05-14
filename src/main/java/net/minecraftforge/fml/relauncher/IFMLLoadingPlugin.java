package net.minecraftforge.fml.relauncher;

import java.lang.annotation.*;
import java.util.*;

public interface IFMLLoadingPlugin {
	String[] getASMTransformerClass();

	String getModContainerClass();

	String getSetupClass();

	void injectData(Map<String, Object> var1);

	String getAccessTransformerClass();

	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.TYPE})
	@interface SortingIndex {
		int value() default 0;
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.TYPE})
	@interface DependsOn {
		String[] value() default {};
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.TYPE})
	@interface Name {
		String value() default "";
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.TYPE})
	@interface MCVersion {
		String value() default "";
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.TYPE})
	@interface TransformerExclusions {
		String[] value() default {""};
	}
}
